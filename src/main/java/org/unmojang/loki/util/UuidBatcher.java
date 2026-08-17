package org.unmojang.loki.util;

import java.net.HttpURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("CallToPrintStackTrace")
public class UuidBatcher {

    public interface Resolver {
        Map<String, String> batchLookup(List<String> usernames) throws Exception;
        String singleLookup(String username) throws Exception;
    }

    public interface Callback {
        void onResolved(String username, String uuid);
    }

    public static class EndpointUnavailableException extends Exception {
        private static final long serialVersionUID = 1L;
        public EndpointUnavailableException(String message) {
            super(message);
        }
    }

    public static class RateLimitedException extends Exception {
        private static final long serialVersionUID = 1L;
        public final long retryAfterMs;
        public RateLimitedException(long retryAfterMs) {
            super("Rate limited, retry after " + retryAfterMs + "ms");
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static final int BATCH_SIZE = 10;
    private static final long FLUSH_INTERVAL_MS = 1000L;
    // > Most of the API has a per-IP ratelimit of 200 requests per 2 minutes.
    // - https://minecraft.wiki/w/Mojang_API
    private static final long SINGLE_INTERVAL_MS = 120000L / 180;
    private static final long DEFAULT_RATE_LIMIT_MS = 60000L;
    private static final int MAX_RATE_LIMIT_RETRIES = 3;

    private final Resolver resolver;
    private final ConcurrentHashMap<String, Request> pending = new ConcurrentHashMap<String, Request>();
    private final LinkedBlockingQueue<Request> queue = new LinkedBlockingQueue<Request>();
    private final ExecutorService fallbackPool;
    private volatile boolean batchBroken = false;

    private final Object throttleLock = new Object();
    private long nextSingleTime = 0L;
    private volatile long rateLimitUntil = 0L;

    public UuidBatcher(final String name, Resolver resolver) {
        this.resolver = resolver;
        this.fallbackPool = Executors.newFixedThreadPool(4, daemonFactory(name + "-Single"));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory(name + "-Batch"));
        scheduler.scheduleAtFixedRate(new Runnable() {
            public void run() {
                try { flush(); } catch (Throwable t) { t.printStackTrace(); }
            }
        }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void resolve(String username, Callback cb) {
        Request req = register(username);
        boolean done;
        String uuid;
        synchronized (req) {
            done = req.done;
            uuid = req.uuid;
            if (!done && cb != null) req.callbacks.add(cb);
        }
        if (done && cb != null) cb.onResolved(username, uuid);
    }

    public String getUUID(String username) throws Exception {
        Request req = register(username);
        req.latch.await();
        if (req.error != null) throw req.error;
        return req.uuid;
    }

    private Request register(String username) {
        Request req = new Request(username);
        Request existing = pending.putIfAbsent(username, req);
        if (existing != null) return existing;
        if (batchBroken) submitSingle(req);
        else queue.add(req);
        return req;
    }

    private void flush() {
        if (System.currentTimeMillis() < rateLimitUntil) return; // back off after a 429
        if (queue.isEmpty()) return;

        List<Request> batch = new ArrayList<Request>(BATCH_SIZE);
        Request r;
        while (batch.size() < BATCH_SIZE && (r = queue.poll()) != null) batch.add(r);
        if (batch.isEmpty()) return;

        List<String> names = new ArrayList<String>(batch.size());
        for (Request request : batch) names.add(request.username);

        Map<String, String> resolved;
        try {
            resolved = resolver.batchLookup(names);
        } catch (RateLimitedException e) {
            rateLimitUntil = System.currentTimeMillis() + e.retryAfterMs;
            for (Request request : batch) {
                if (request.rateLimitAttempts.getAndIncrement() < MAX_RATE_LIMIT_RETRIES) {
                    queue.add(request);
                } else {
                    request.complete(null, e);
                    pending.remove(request.username, request);
                }
            }
            return;
        } catch (Exception e) {
            if (e instanceof EndpointUnavailableException) batchBroken = true;
            for (Request request : batch) submitSingle(request);
            return;
        }

        if (resolved.isEmpty()) {
            // maybe batch lookup isn't supported here?
            for (Request request : batch) submitSingle(request);
            return;
        }

        for (Request req : batch) {
            req.complete(resolved.get(req.username.toLowerCase(Locale.ENGLISH)), null);
            pending.remove(req.username, req);
        }
    }

    private void submitSingle(final Request req) {
        fallbackPool.execute(new Runnable() {
            public void run() {
                String uuid = null;
                Exception error = null;
                try {
                    throttleSingle();
                    uuid = resolver.singleLookup(req.username);
                } catch (RateLimitedException e) {
                    rateLimitUntil = System.currentTimeMillis() + e.retryAfterMs;
                    if (req.rateLimitAttempts.getAndIncrement() < MAX_RATE_LIMIT_RETRIES) {
                        submitSingle(req);
                        return;
                    }
                    error = e;
                } catch (Exception e) {
                    error = e;
                }
                req.complete(uuid, error);
                pending.remove(req.username, req);
            }
        });
    }

    private void throttleSingle() {
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            long waitUntil = Math.max(nextSingleTime, rateLimitUntil);
            if (waitUntil > now) {
                try { Thread.sleep(waitUntil - now); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            nextSingleTime = System.currentTimeMillis() + SINGLE_INTERVAL_MS;
        }
    }

    public static RateLimitedException rateLimited(HttpURLConnection conn) {
        long retryAfterMs = DEFAULT_RATE_LIMIT_MS;
        String header = conn.getHeaderField("Retry-After");
        if (header != null) {
            header = header.trim();
            try {
                retryAfterMs = Long.parseLong(header) * 1000L;
            } catch (NumberFormatException notSeconds) {
                try {
                    SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);
                    fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
                    long delta = fmt.parse(header).getTime() - System.currentTimeMillis();
                    if (delta > 0) retryAfterMs = delta;
                } catch (Exception ignored) {}
            }
        }
        return new RateLimitedException(retryAfterMs);
    }

    private static ThreadFactory daemonFactory(final String name) {
        return new ThreadFactory() {
            private final AtomicInteger threadId = new AtomicInteger(1);
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + threadId.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
    }

    private static final class Request {
        final String username;
        final List<Callback> callbacks = new ArrayList<Callback>();
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String uuid;
        volatile Exception error;
        volatile boolean done;
        final AtomicInteger rateLimitAttempts = new AtomicInteger(); // touched from batch and fallback-pool threads

        Request(String username) { this.username = username; }

        void complete(String uuid, Exception error) {
            List<Callback> toCall;
            synchronized (this) {
                if (done) return;
                this.uuid = uuid;
                this.error = error;
                this.done = true;
                toCall = new ArrayList<Callback>(callbacks);
                callbacks.clear();
            }
            latch.countDown();
            for (Callback callback : toCall) {
                try {
                    callback.onResolved(username, uuid);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
