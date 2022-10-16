package ok.dht.test.shestakova;

import ok.dht.ServiceConfig;
import ok.dht.test.shestakova.exceptions.MethodNotAllowedException;
import ok.dht.test.shestakova.exceptions.NullKeyException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.server.SelectorThread;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class DemoHttpServer extends HttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoHttpServer.class);
    private final HttpClient httpClient;
    private final ServiceConfig serviceConfig;
    private final ExecutorService workersPool;
    private final CircuitBreakerImpl circuitBreaker;

    public DemoHttpServer(HttpServerConfig config, HttpClient httpClient, ExecutorService workersPool,
                          ServiceConfig serviceConfig, Object... routers) throws IOException {
        super(config, routers);
        this.httpClient = httpClient;
        this.serviceConfig = serviceConfig;
        this.workersPool = workersPool;
        this.circuitBreaker = new CircuitBreakerImpl(serviceConfig, httpClient);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        String key;
        try {
            key = getKeyFromRequest(request, session);
        } catch (NullKeyException e) {
            return;
        }

        String targetNode = serviceConfig.clusterUrls().size() > 1 ? getClusterByRendezvousHashing(key)
                : serviceConfig.selfUrl();
        if (targetNode == null) {
            LOGGER.error("There are no available nodes in the cluster!");
            return;
        }
        if (!targetNode.equals(serviceConfig.selfUrl())) {
            HttpRequest httpRequest;
            try {
                httpRequest = buildHttpRequest(key, targetNode, request);
            } catch (MethodNotAllowedException e) {
                LOGGER.error("Method not allowed " + serviceConfig.selfUrl() + " method: " + request.getMethod());
                Response response = new Response(
                        Response.METHOD_NOT_ALLOWED,
                        Response.EMPTY
                );
                try {
                    session.sendResponse(response);
                } catch (IOException ex) {
                    LOGGER.error("Error while sending response in server " + serviceConfig.selfUrl());
                }
                return;
            }
            try {
                CompletableFuture<HttpResponse<byte[]>> responseCompletableFuture = httpClient
                        .sendAsync(
                                httpRequest,
                                HttpResponse.BodyHandlers.ofByteArray()
                        );
                getResponse(responseCompletableFuture, session);
                return;
            } catch (InterruptedException | IOException e) {
                LOGGER.error("Error while working with response in server " + serviceConfig.selfUrl());
            }
        }

        workersPool.execute(() -> {
            try {
                super.handleRequest(request, session);
            } catch (IOException e) {
                LOGGER.error("Error while handling request in " + serviceConfig.selfUrl());
            }
        });
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        try {
            getKeyFromRequest(request, session);
        } catch (NullKeyException e) {
            return;
        }
        Response response;
        int requestMethod = request.getMethod();
        if (requestMethod != Request.METHOD_GET && requestMethod != Request.METHOD_PUT
                && requestMethod != Request.METHOD_DELETE) {
            response = new Response(
                    Response.METHOD_NOT_ALLOWED,
                    Response.EMPTY
            );
            session.sendResponse(response);
            return;
        }
        response = new Response(
                Response.SERVICE_UNAVAILABLE,
                Response.EMPTY
        );
        session.sendResponse(response);
    }

    @Override
    public synchronized void stop() {
        for (SelectorThread selectorThread : selectors) {
            if (!selectorThread.isAlive()) {
                continue;
            }
            for (Session session : selectorThread.selector) {
                session.close();
            }
        }
        circuitBreaker.doShutdownNow();
        super.stop();
    }

    private String getKeyFromRequest(Request request, HttpSession session) throws NullKeyException {
        String key = request.getParameter("id=");
        if (key == null || key.isEmpty()) {
            try {
                Response response = new Response(
                        Response.BAD_REQUEST,
                        Response.EMPTY
                );
                session.sendResponse(response);
                throw new NullKeyException();
            } catch (IOException e) {
                LOGGER.error("Error while sending response from " + serviceConfig.selfUrl());
            }
        }
        return key;
    }

    public void putNodesIllnessInfo(String node, boolean isIll) {
        circuitBreaker.putNodesIllnessInfo(node, isIll);
    }

    private HttpRequest.Builder request(String nodeUrl, String path) {
        return HttpRequest.newBuilder(URI.create(nodeUrl + path));
    }

    private HttpRequest.Builder requestForKey(String nodeUrl, String key) {
        return request(nodeUrl, "/v0/entity?id=" + key);
    }

    private HttpRequest buildHttpRequest(String key, String targetCluster, Request request)
            throws MethodNotAllowedException {
        if (request.getMethod() != Request.METHOD_GET && request.getMethod() != Request.METHOD_PUT
                && request.getMethod() != Request.METHOD_DELETE) {
            throw new MethodNotAllowedException();
        }

        HttpRequest.Builder httpRequest = requestForKey(targetCluster, key);
        int requestMethod = request.getMethod();
        if (requestMethod == Request.METHOD_GET) {
            httpRequest.GET();
        } else if (requestMethod == Request.METHOD_PUT) {
            httpRequest.PUT(HttpRequest.BodyPublishers.ofByteArray(request.getBody()));
        } else if (requestMethod == Request.METHOD_DELETE) {
            httpRequest.DELETE();
        }
        return httpRequest.build();
    }

    private void getResponse(CompletableFuture<HttpResponse<byte[]>> responseCompletableFuture, HttpSession session)
            throws InterruptedException, IOException {
        try {
            HttpResponse<byte[]> response = responseCompletableFuture.get(1, TimeUnit.SECONDS);
            session.sendResponse(new Response(
                    String.valueOf(response.statusCode()),
                    response.body()
            ));
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.error("Error while working with response in " + serviceConfig.selfUrl());
            circuitBreaker.incrementFallenRequestsCount();
            session.sendResponse(new Response(
                    Response.SERVICE_UNAVAILABLE,
                    Response.EMPTY
            ));
        }
    }

    private String getClusterByRendezvousHashing(String key) {
        long hashVal = Long.MIN_VALUE;
        String cluster = null;

        for (String nodeUrl : serviceConfig.clusterUrls()) {
            if (circuitBreaker.isNodeIll(nodeUrl)) {
                continue;
            }
            int tmpHash = Hash.murmur3(nodeUrl + key);
            if (tmpHash > hashVal) {
                hashVal = tmpHash;
                cluster = nodeUrl;
            }
        }
        return cluster;
    }
}
