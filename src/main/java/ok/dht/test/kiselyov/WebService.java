package ok.dht.test.kiselyov;

import ok.dht.Service;
import ok.dht.ServiceConfig;
import ok.dht.test.ServiceFactory;
import ok.dht.test.kiselyov.dao.BaseEntry;
import ok.dht.test.kiselyov.dao.Config;
import ok.dht.test.kiselyov.dao.impl.PersistentDao;
import ok.dht.test.kiselyov.util.ClusterNode;
import ok.dht.test.kiselyov.util.CustomLinkedBlockingDeque;
import ok.dht.test.kiselyov.util.InternalClient;
import ok.dht.test.kiselyov.util.NodeDeterminer;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.server.AcceptorConfig;
import one.nio.server.SelectorThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class WebService implements Service {

    private final ServiceConfig config;
    private HttpServer server;
    private PersistentDao dao;
    private static final int FLUSH_THRESHOLD_BYTES = 1 << 20;
    private ExecutorService executorService;
    private static final int CORE_POOL_SIZE = 64;
    private static final int MAXIMUM_POOL_SIZE = 64;
    private static final int DEQUE_CAPACITY = 64;
    private List<Future<?>> tasks;
    private NodeDeterminer nodeDeterminer;
    private InternalClient internalClient;
    private static final Logger LOGGER = LoggerFactory.getLogger(WebService.class);

    public WebService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<?> start() throws IOException {
        if (Files.notExists(config.workingDir())) {
            Files.createDirectory(config.workingDir());
        }
        dao = new PersistentDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        List<ClusterNode> clusterNodes = new ArrayList<>();
        for (String nodeUrl : config.clusterUrls()) {
            clusterNodes.add(new ClusterNode(nodeUrl));
        }
        nodeDeterminer = new NodeDeterminer(clusterNodes);
        internalClient = new InternalClient();
        tasks = new ArrayList<>();
        executorService = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, 0L,
                TimeUnit.MILLISECONDS, new CustomLinkedBlockingDeque<>(DEQUE_CAPACITY));
        server = new HttpServer(createConfigFromPort(config.selfPort())) {
            @Override
            public void handleRequest(Request request, HttpSession session) throws IOException {
                try {
                    tasks.add(executorService.submit(() -> tryHandleRequest(request, session)));
                } catch (RejectedExecutionException e) {
                    LOGGER.error("Cannot execute task: ", e);
                    session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
                }
            }

            @Override
            public void handleDefault(Request request, HttpSession session) throws IOException {
                String resultCode = request.getMethod() == Request.METHOD_GET
                        || request.getMethod() == Request.METHOD_PUT
                        ? Response.BAD_REQUEST : Response.METHOD_NOT_ALLOWED;
                Response defaultResponse = new Response(resultCode, Response.EMPTY);
                session.sendResponse(defaultResponse);
            }

            @Override
            public synchronized void stop() {
                for (SelectorThread selectorThread : selectors) {
                    if (selectorThread.isAlive()) {
                        for (Session session : selectorThread.selector) {
                            session.socket().close();
                        }
                    }
                }
                super.stop();
            }

            private void tryHandleRequest(Request request, HttpSession session) {
                try {
                    String id = request.getParameter("id=");
                    if (id == null || id.isBlank()) {
                        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                        return;
                    }
                    ClusterNode targetClusterNode = nodeDeterminer.getNodeUrl(id);
                    if (targetClusterNode.getUrl().equals(config.selfUrl())) {
                        super.handleRequest(request, session);
                        return;
                    }
                    sendResponse(request, session, id, targetClusterNode);
                } catch (IOException e) {
                    LOGGER.error("Error handling request.", e);
                }
            }

            private void sendResponse(Request request, HttpSession session, String id, ClusterNode targetClusterNode) {
                HttpResponse<byte[]> getResponse;
                try {
                    try {
                        getResponse = internalClient.sendRequestToNode(request, targetClusterNode, id)
                                .get(100, TimeUnit.MILLISECONDS);
                        switch (request.getMethod()) {
                            case Request.METHOD_GET -> {
                                switch (getResponse.statusCode()) {
                                    case HttpURLConnection.HTTP_OK ->
                                            session.sendResponse(new Response(Response.OK, getResponse.body()));
                                    case HttpURLConnection.HTTP_NOT_FOUND ->
                                            session.sendResponse(new Response(Response.NOT_FOUND, Response.EMPTY));
                                    default ->
                                            session.sendResponse(new Response(String.valueOf(getResponse.statusCode()),
                                                    Response.EMPTY));
                                }
                            }
                            case Request.METHOD_PUT -> {
                                if (getResponse.statusCode() == HttpURLConnection.HTTP_CREATED) {
                                    session.sendResponse(new Response(Response.CREATED, Response.EMPTY));
                                }
                                session.sendResponse(new Response(String.valueOf(getResponse.statusCode()),
                                        Response.EMPTY));
                            }
                            case Request.METHOD_DELETE -> {
                                if (getResponse.statusCode() == HttpURLConnection.HTTP_ACCEPTED) {
                                    session.sendResponse(new Response(Response.ACCEPTED, Response.EMPTY));
                                }
                                session.sendResponse(new Response(String.valueOf(getResponse.statusCode()),
                                        Response.EMPTY));
                            }
                            default -> {
                                LOGGER.error("Unsupported request method: {}", request.getMethodName());
                                throw new InternalError("Unsupported request method: " + request.getMethodName());
                            }
                        }
                    } catch (ExecutionException e) {
                        LOGGER.warn("Cluster node is unavailable.", e);
                        session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
                    }
                } catch (URISyntaxException e) {
                    LOGGER.error("URI error.", e);
                } catch (InterruptedException e) {
                    LOGGER.error("Error while getting response.");
                } catch (IOException e) {
                    LOGGER.error("Error handling request.", e);
                } catch (TimeoutException e) {
                    LOGGER.error("No response from cluster node.", e);
                }
            }
        };
        server.start();
        server.addRequestHandlers(this);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> stop() throws IOException {
        server.stop();
        for (Future<?> task : tasks) {
            if (!task.isCancelled()) {
                task.cancel(true);
            }
        }
        executorService.shutdown();
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response handleGet(@Param(value = "id") String id) {
        BaseEntry<byte[]> result;
        try {
            result = dao.get(id.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.error("GET operation with id {} from GET request failed.", id, e);
            return new Response(Response.INTERNAL_ERROR, e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return new Response(Response.OK, result.value());
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response handlePut(@Param(value = "id") String id, Request putRequest) {
        try {
            dao.upsert(new BaseEntry<>(id.getBytes(StandardCharsets.UTF_8), putRequest.getBody()));
        } catch (Exception e) {
            LOGGER.error("UPSERT operation with id {} from PUT request failed.", id, e);
            return new Response(Response.INTERNAL_ERROR, e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response handleDelete(@Param(value = "id") String id) {
        try {
            dao.upsert(new BaseEntry<>(id.getBytes(StandardCharsets.UTF_8), null));
        } catch (Exception e) {
            LOGGER.error("UPSERT operation with id {} from DELETE request failed.", id, e);
            return new Response(Response.INTERNAL_ERROR, e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static HttpServerConfig createConfigFromPort(int port) {
        HttpServerConfig httpConfig = new HttpServerConfig();
        AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        acceptor.reusePort = true;
        httpConfig.acceptors = new AcceptorConfig[]{acceptor};
        return httpConfig;
    }

    @ServiceFactory(stage = 3, week = 1, bonuses = "SingleNodeTest#respectFileFolder")
    public static class Factory implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new WebService(config);
        }
    }
}
