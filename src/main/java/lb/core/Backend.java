package lb.core;

import java.util.concurrent.atomic.*;

/** One upstream server plus its live state (connections, health, counters). */
public final class Backend {
    public final String host;
    public final int port;
    public final int weight;
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean healthy = true;
    public final LongAdder requests = new LongAdder();
    public final LongAdder errors = new LongAdder();

    public Backend(String host, int port, int weight) {
        if (weight < 1) throw new IllegalArgumentException("weight must be >= 1");
        this.host = host; this.port = port; this.weight = weight;
    }

    /** Parses host:port[:weight]. */
    public static Backend parse(String spec) {
        String[] p = spec.split(":");
        return new Backend(p[0], Integer.parseInt(p[1]), p.length > 2 ? Integer.parseInt(p[2]) : 1);
    }

    public String id() { return host + ":" + port; }
    public int active() { return active.get(); }
    public void acquire() { active.incrementAndGet(); requests.increment(); }
    public void release() { active.decrementAndGet(); }
    public boolean healthy() { return healthy; }
    public void setHealthy(boolean h) { healthy = h; }
    @Override public String toString() { return id() + "(w=" + weight + ")"; }
}
