package lb.core;

import java.net.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** Active TCP health checks; flips Backend.healthy as servers go down/up. */
public final class HealthChecker {
    private static final Logger LOG = Logger.getLogger("lb.health");
    private final BackendPool pool;
    private final int intervalMs;
    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "health-checker"); t.setDaemon(true); return t; });

    public HealthChecker(BackendPool pool, int intervalMs) { this.pool = pool; this.intervalMs = intervalMs; }

    public void start() { ses.scheduleWithFixedDelay(this::checkAll, 0, intervalMs, TimeUnit.MILLISECONDS); }
    public void stop() { ses.shutdownNow(); }

    void checkAll() {
        for (Backend b : pool.all()) {
            boolean up;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(b.host, b.port), 1000);
                up = true;
            } catch (Exception e) { up = false; }
            if (up != b.healthy()) LOG.info("backend " + b.id() + (up ? " is UP" : " is DOWN"));
            b.setHealthy(up);
        }
    }
}
