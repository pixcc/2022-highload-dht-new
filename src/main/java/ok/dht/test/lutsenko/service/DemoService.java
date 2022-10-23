package ok.dht.test.lutsenko.service;

import ok.dht.Service;
import ok.dht.ServiceConfig;
import ok.dht.test.ServiceFactory;
import ok.dht.test.lutsenko.dao.common.DaoConfig;
import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.server.SelectorThread;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class DemoService implements Service {

    private static final int MAX_NODES_NUMBER = 1000; // was limited in lection
    private static final int VIRTUAL_NODES_NUMBER = 10;
    private static final int HASH_SPACE = MAX_NODES_NUMBER * VIRTUAL_NODES_NUMBER * 360;
    private static final int PROXY_RESPONSES_EXECUTOR_THREADS = 16;
    private static final String DAO_PREFIX = "dao";
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final Logger LOG = LoggerFactory.getLogger(DemoService.class);

    private final Path daoPath;
    private final ServiceConfig config;
    private final int selfNodeNumber;
    private final Map<Integer, String> nodesNumberToUrlMap = new HashMap<>();
    private final NavigableMap<Integer, Integer> virtualNodes = new TreeMap<>(); // node position to node number

    //--------------------------Non final fields due to stop / close / shutdown in stop()--------------------------\\
    private HttpServer server;
    private ExecutorService requestExecutor;
    private ExecutorService replicasResponsesExecutor;
    private DaoHandler daoHandler;
    private ProxyHandler proxyHandler;
    //--------------------------------------------------------------------------------------------------------------\\

    public DemoService(ServiceConfig config) {
        if (config.clusterUrls().size() > MAX_NODES_NUMBER) {
            throw new IllegalArgumentException("There can`t be more " + MAX_NODES_NUMBER + " nodes");
        }
        this.config = config;
        this.daoPath = config.workingDir().resolve(DAO_PREFIX);
        this.selfNodeNumber = config.clusterUrls().indexOf(config.selfUrl());
        List<String> clusterUrls = config.clusterUrls();
        for (String url : clusterUrls) {
            nodesNumberToUrlMap.put(clusterUrls.indexOf(url), url);
        }
        fillVirtualNodes();
    }

    @Override
    public CompletableFuture<?> start() throws IOException {
        requestExecutor = RequestExecutorService.requestExecutorDiscardOldest();
        replicasResponsesExecutor = Executors.newFixedThreadPool(PROXY_RESPONSES_EXECUTOR_THREADS);
        daoHandler = new DaoHandler(DaoConfig.defaultConfig(daoPath));
        proxyHandler = new ProxyHandler();

        server = new HttpServer(ServiceUtils.createConfigFromPort(config.selfPort())) {

            @Override
            public void handleRequest(Request request, HttpSession session) {
                long requestTime = System.currentTimeMillis();
                requestExecutor.execute(new SessionRunnable(session, () -> {
                    if (isProxyRequestAndHandle(request, session)) {
                        return;
                    }
                    RequestParser requestParser = new RequestParser(request)
                            .checkPath()
                            .checkSuccessStatusCodes()
                            .checkId()
                            .checkAckFrom(config.clusterUrls().size());
                    if (requestParser.isFailed()) {
                        ServiceUtils.sendResponse(session, requestParser.failStatus());
                        return;
                    }
                    List<Integer> successStatuses = requestParser.successStatuses();
                    String id = requestParser.id();
                    int ack = requestParser.ack();
                    int from = requestParser.from();
                    List<CompletableFuture<Response>> replicasResponsesFutures = new ArrayList<>(from);
                    for (int nodeNumber : getReplicaNodeNumbers(id, from)) {
                        // both proceed() methods wrapped with try / catch Exception
                        replicasResponsesFutures.add(nodeNumber == selfNodeNumber
                                ? daoHandler.proceed(id, request, requestTime)
                                : proxyHandler.proceed(request, nodesNumberToUrlMap.get(nodeNumber), requestTime)
                        );
                    }
                    handleReplicasResponses(session, replicasResponsesFutures, successStatuses, ack, from);
                }));
            }

            @Override
            public synchronized void stop() {
                for (SelectorThread thread : selectors) {
                    for (Session session : thread.selector) {
                        session.close();
                    }
                }
                selectors = new SelectorThread[0];
                super.stop();
            }
        };
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    private void handleReplicasResponses(HttpSession session,
                                         List<CompletableFuture<Response>> replicasResponsesFutures,
                                         List<Integer> successStatuses,
                                         int ack,
                                         int from
    ) {
        // Может возникнуть вопрос зачем нужны счетчики, ведь можно использовать размер мапы responses?
        // Чтобы не класть в мапу лишние значения, если requestTime не важен, например при PUT и DELETE.
        // Также при GET запросах с помощью CustomHeaders.REQUEST_TIME различается ситуации когда ключ не найден, тогда
        // requestTime невозможно записать, так как в dao его нет и в мапу такие ответы не кладутся, но successCounter
        // увеличивается. Когда найдена могила, то requestTime указывается и значение в мапу добавляется.
        // Если мапа пустая и CustomHeaders.REQUEST_TIME отсутствует, то requestTime принимаем за 0 и делаем одну
        // запись в мапу. Также счетчики позволяют прервать выполнение запросов к другим репликам если
        // кворум уже набран или количество отказов гарантированно не позволит его собрать.
        AtomicInteger failsCounter = new AtomicInteger(0);
        AtomicInteger successCounter = new AtomicInteger(0);
        NavigableMap<Long, Response> responses = new ConcurrentSkipListMap<>();
        for (CompletableFuture<Response> replicaResponseFuture : replicasResponsesFutures) {
            replicasResponsesExecutor.execute(() -> {
                try {
                    Response response = replicaResponseFuture.get();
                    if (successStatuses.contains(response.getStatus())) {
                        String requestTimeHeaderValue = response.getHeader(CustomHeaders.REQUEST_TIME);
                        if (requestTimeHeaderValue != null) {
                            responses.put(Long.parseLong(requestTimeHeaderValue), response);
                        } else if (responses.isEmpty()) {
                            responses.put(0L, response);
                        }
                        if (successCounter.incrementAndGet() == ack) {
                            ServiceUtils.sendResponse(session, responses.lastEntry().getValue());
                            cancelFutures(replicasResponsesFutures);
                        }
                    } else if (failsCounter.incrementAndGet() == from - ack + 1) {
                        ServiceUtils.sendResponse(session, new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY));
                        cancelFutures(replicasResponsesFutures);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    if (failsCounter.incrementAndGet() == from - ack + 1) {
                        ServiceUtils.sendResponse(session, new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY));
                        cancelFutures(replicasResponsesFutures);
                    }
                }
            });
        }
    }

    @Override
    public CompletableFuture<?> stop() throws IOException {
        try {
            RequestExecutorService.shutdownAndAwaitTermination(requestExecutor);
        } catch (TimeoutException e) {
            LOG.warn("Request executor await termination too long");
        }
        proxyHandler.close();
        server.stop();
        daoHandler.close();
        return CompletableFuture.completedFuture(null);
    }

    private boolean isProxyRequestAndHandle(Request request, HttpSession session) {
        String proxyRequestTimeHeaderValue = request.getHeader(CustomHeaders.PROXY_REQUEST_TIME);
        if (proxyRequestTimeHeaderValue == null) {
            return false;
        }
        long requestTime = Long.parseLong(proxyRequestTimeHeaderValue);
        daoHandler.handle(request.getParameter(RequestParser.ID_PARAM_NAME), request, session, requestTime);
        return true;
    }

    private void fillVirtualNodes() {
        int collisionCounter = 1;
        for (String url : config.clusterUrls()) {
            int nodeNumber = config.clusterUrls().indexOf(url);
            for (int i = 0; i < VIRTUAL_NODES_NUMBER; i++) {
                int nodePosition = calculateHashRingPosition(url + i + collisionCounter);
                while (virtualNodes.containsKey(nodePosition)) {
                    nodePosition = calculateHashRingPosition(url + i + collisionCounter++);
                }
                virtualNodes.put(nodePosition, nodeNumber);
            }
        }
    }

    private Set<Integer> getReplicaNodeNumbers(String key, int from) {
        Map.Entry<Integer, Integer> virtualNode = virtualNodes.ceilingEntry(calculateHashRingPosition(key));
        if (virtualNode == null) {
            virtualNode = virtualNodes.firstEntry();
        }
        Set<Integer> replicaNodesPositions = new HashSet<>();
        Collection<Integer> nextVirtualNodesPositions = virtualNodes.tailMap(virtualNode.getKey()).values();
        addReplicaNodePositions(replicaNodesPositions, nextVirtualNodesPositions, from);
        if (replicaNodesPositions.size() < from) {
            addReplicaNodePositions(replicaNodesPositions, virtualNodes.values(), from);
        }
        if (replicaNodesPositions.size() != from) {
            throw new IllegalArgumentException("Can`t find from amount of replica nodes positions");
        }
        return replicaNodesPositions;
    }

    private int calculateHashRingPosition(String url) {
        return Math.abs(Hash.murmur3(url)) % HASH_SPACE;
    }

    private static void addReplicaNodePositions(Set<Integer> replicaNodesPositions,
                                                Collection<Integer> virtualNodesPositions,
                                                int from
    ) {
        for (Integer nodePosition : virtualNodesPositions) {
            replicaNodesPositions.add(nodePosition);
            if (replicaNodesPositions.size() == from) {
                break;
            }
        }
    }

    private static void cancelFutures(List<CompletableFuture<Response>> replicasResponsesFutures) {
        for (CompletableFuture<Response> replicasResponsesFuture : replicasResponsesFutures) {
            replicasResponsesFuture.cancel(true);
        }
    }

    @ServiceFactory(stage = 4, week = 1, bonuses = "SingleNodeTest#respectFileFolder")
    public static class Factory implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new DemoService(config);
        }
    }
}
