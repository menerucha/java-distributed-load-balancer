package lb;

import lb.admin.AdminServer;
import lb.config.Config;
import lb.core.*;
import lb.metrics.Metrics;
import lb.proxy.*;
import lb.ratelimit.RateLimiter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Wires all components together. Usable embedded (tests) or via {@link Main}. */
public final class LoadBalancer {
    public final Config cfg;
    public final BackendPool pool;
    public final RateLimiter limiter;
    public final Metrics metrics = new Metrics();
    private final Proxy proxy;
    private final AdminServer admin;
    private final HealthChecker health;
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "limiter-janitor"); t.setDaemon(true); return t; });

    public LoadBalancer(Config cfg) throws IOException {
        this.cfg = cfg;
        List<Backend> backends = new ArrayList<>();
        for (String s : cfg.backends) backends.add(Backend.parse(s));
        if (backends.isEmpty()) throw new IllegalArgumentException("No backends configured");
        this.pool = new BackendPool(backends, cfg.algorithm);
        this.limiter = new RateLimiter(cfg.rateLimitEnabled, cfg.rateLimitCapacity, cfg.rateLimitRefillPerSec, cfg.rateLimitOverrides);
        this.proxy = cfg.mode == Config.Mode.L4 ? new L4Proxy(cfg, pool, limiter, metrics) : new L7Proxy(cfg, pool, limiter, metrics);
        this.admin = new AdminServer(cfg.adminPort, cfg, pool, limiter, metrics);
        this.health = new HealthChecker(pool, cfg.healthIntervalMs);
    }

    public void start() throws IOException {
        health.start();
        admin.start();
        proxy.start();
        janitor.scheduleWithFixedDelay(() -> limiter.evictIdle(TimeUnit.MINUTES.toNanos(5)), 1, 1, TimeUnit.MINUTES);
    }

    public void stop() { proxy.stop(); admin.stop(); health.stop(); janitor.shutdownNow(); }
    public int port() { return proxy.port(); }
    public int adminPort() { return admin.port(); }
}
