package lb.ratelimit;

import java.util.function.LongSupplier;

/** Classic token bucket: holds up to {@code capacity} tokens, refilled continuously at {@code refillPerSec}. */
public final class TokenBucket {
    private final double capacity, refillPerNano;
    private final LongSupplier clock;
    private double tokens;
    private long last;
    private long lastAccess;

    public TokenBucket(double capacity, double refillPerSec) { this(capacity, refillPerSec, System::nanoTime); }

    public TokenBucket(double capacity, double refillPerSec, LongSupplier nanoClock) {
        if (capacity < 1 || refillPerSec <= 0) throw new IllegalArgumentException("capacity>=1 and refill>0 required");
        this.capacity = capacity; this.refillPerNano = refillPerSec / 1e9; this.clock = nanoClock;
        this.tokens = capacity; this.last = this.lastAccess = nanoClock.getAsLong();
    }

    public synchronized boolean tryConsume() {
        long now = clock.getAsLong();
        tokens = Math.min(capacity, tokens + (now - last) * refillPerNano);
        last = lastAccess = now;
        if (tokens >= 1) { tokens -= 1; return true; }
        return false;
    }

    /** Seconds until one token is available (for the Retry-After header). */
    public synchronized long retryAfterSeconds() {
        double deficit = Math.max(0, 1 - tokens);
        return Math.max(1, (long) Math.ceil(deficit / (refillPerNano * 1e9)));
    }

    public synchronized long lastAccessNanos() { return lastAccess; }
}
