package lb.ratelimit;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Per-client token-bucket throttling with optional per-client overrides and idle-bucket eviction. */
public final class RateLimiter {
    private final boolean enabled;
    private final double defCapacity, defRefill;
    private final Map<String, double[]> overrides;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(boolean enabled, double capacity, double refillPerSec, Map<String, double[]> overrides) {
        this(enabled, capacity, refillPerSec, overrides, System::nanoTime);
    }

    public RateLimiter(boolean enabled, double capacity, double refillPerSec, Map<String, double[]> overrides, LongSupplier clock) {
        this.enabled = enabled; this.defCapacity = capacity; this.defRefill = refillPerSec;
        this.overrides = overrides == null ? Map.of() : overrides; this.clock = clock;
    }

    public boolean allow(String clientKey) {
        if (!enabled) return true;
        return bucket(clientKey).tryConsume();
    }

    public long retryAfterSeconds(String clientKey) { return bucket(clientKey).retryAfterSeconds(); }

    private TokenBucket bucket(String key) {
        return buckets.computeIfAbsent(key, k -> {
            double[] o = overrides.get(k);   // {refillPerSec, capacity}
            return o != null ? new TokenBucket(o[1], o[0], clock) : new TokenBucket(defCapacity, defRefill, clock);
        });
    }

    public int trackedClients() { return buckets.size(); }

    /** Drops buckets idle for longer than {@code idleNanos} (they'd be full again anyway). */
    public void evictIdle(long idleNanos) {
        long now = clock.getAsLong();
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccessNanos() > idleNanos);
    }

    public boolean enabled() { return enabled; }
}
