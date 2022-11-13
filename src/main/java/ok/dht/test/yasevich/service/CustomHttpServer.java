package ok.dht.test.yasevich.service;

import ok.dht.ServiceConfig;
import ok.dht.test.yasevich.utils.AlmostLifoQueue;
import ok.dht.test.yasevich.utils.TimeStampingDao;
import ok.dht.test.yasevich.artyomdrozdov.MemorySegmentDao;
import ok.dht.test.yasevich.dao.Config;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.server.SelectorThread;

import java.io.IOException;
import java.util.concurrent.*;

class CustomHttpServer extends HttpServer {
    private static final int CPUs = Runtime.getRuntime().availableProcessors();

    private final ServiceConfig serviceConfig;
    private final TimeStampingDao timeStampingDao;
    private final ReplicationManager replicationManager;

    private final ExecutorService httpClientPool = Executors.newFixedThreadPool(16);

    private final ExecutorService workersPool = new ThreadPoolExecutor(CPUs, CPUs, 0L,
            TimeUnit.MILLISECONDS, new AlmostLifoQueue(ServiceImpl.POOL_QUEUE_SIZE, 3));


    public CustomHttpServer(
            HttpServerConfig config,
            ServiceConfig serviceConfig,
            Object... routers) throws IOException {
        super(config, routers);
        this.serviceConfig = serviceConfig;
        this.timeStampingDao = new TimeStampingDao(
                new MemorySegmentDao(new Config(serviceConfig.workingDir(), ServiceImpl.FLUSH_THRESHOLD)));
        RandevouzHashingRouter router = new RandevouzHashingRouter(serviceConfig.clusterUrls(), httpClientPool);
        this.replicationManager = new ReplicationManager(timeStampingDao, router, serviceConfig.selfUrl());
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String key = request.getParameter("id=");
        String coordinatorTimestamp = request.getHeader(ServiceImpl.COORDINATOR_TIMESTAMP_HEADER + ':');
        if (coordinatorTimestamp != null) {
            submitOrSendUnavailable(workersPool, session, () -> {
                try {
                    long time = Long.parseLong(coordinatorTimestamp);
                    Response response = handleInnerRequest(request, key, time);
                    ServiceImpl.sendResponse(session, response);
                } catch (NumberFormatException e) {
                    ServiceImpl.sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
                } catch (IOException e) {
                    ServiceImpl.sendResponse(session, new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                }
            });
            return;
        }

        int ack;
        int from;
        try {
            ReplicationParams params = ReplicationParams.fromHeader(request, serviceConfig.clusterUrls().size());
            ack = params.ack;
            from = params.from;
        } catch (NumberFormatException e) {
            submitOrSendUnavailable(workersPool, session,
                    () -> ServiceImpl.sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY)));
            return;
        }

        submitOrSendUnavailable(workersPool, session, () -> {
            if (!request.getPath().equals("/v0/entity")
                    || key == null || key.isEmpty()
                    || ack > from || ack <= 0) {
                ServiceImpl.sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }
            long time = System.currentTimeMillis();
            replicationManager.handleReplicatingRequest(session, request, key, time, ack, from);
        });
    }

    private static void submitOrSendUnavailable(Executor pool, HttpSession session, Runnable runnable) {
        try {
            pool.execute(runnable);
        } catch (RejectedExecutionException e) {
            ServiceImpl.sendResponse(session, new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    private Response handleInnerRequest(Request request, String id, long time) throws IOException {
        return switch (request.getMethod()) {
            case Request.METHOD_GET -> handleGet(id);
            case Request.METHOD_PUT -> handlePut(id, request, time);
            case Request.METHOD_DELETE -> handleDelete(id, time);
            default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        };
    }

    private Response handleGet(String id) {
        TimeStampingDao.TimeStampedValue entry = timeStampingDao.get(id);
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        if (entry.value == null) {
            return new Response(Response.NOT_FOUND, entry.timeBytes());
        }
        return new Response(Response.OK, entry.wholeToBytes());
    }

    private Response handleDelete(String id, long time) {
        timeStampingDao.upsert(id, null, time);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private Response handlePut(String id, Request request, long time) {
        timeStampingDao.upsert(id, request.getBody(), time);
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Override
    public synchronized void stop() {
        for (SelectorThread thread : selectors) {
            if (!thread.selector.isOpen()) {
                continue;
            }
            for (Session session : thread.selector) {
                session.socket().close();
            }
        }
        workersPool.shutdown();
        httpClientPool.shutdown();
        timeStampingDao.close();
        super.stop();
    }

    private static class ReplicationParams {
        public final int ack;
        public final int from;

        private ReplicationParams(int ack, int from) {
            this.ack = ack;
            this.from = from;
        }

        public static ReplicationParams fromHeader(Request request, int clusterSize) throws NumberFormatException {
            String replicasParam = request.getParameter("replicas=");
            String ackParam = request.getParameter("ack=");
            String fromParam = request.getParameter("from=");
            String[] replicasParams = replicasParam == null ? null : replicasParam.split("/");
            int from = replicasParams != null ? Integer.parseInt(replicasParams[1]) :
                    fromParam != null ? Integer.parseInt(fromParam) : clusterSize;
            int ack = replicasParams != null ? Integer.parseInt(replicasParams[0]) :
                    ackParam != null ? Integer.parseInt(ackParam) :
                            from / 2 + 1 <= clusterSize ? from / 2 + 1 : from;
            return new ReplicationParams(ack, from);
        }

    }

}
