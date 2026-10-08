package lb.core;

import lb.algo.*;
import java.util.*;

/** Holds the backends and the currently active routing strategy (hot-swappable). */
public final class BackendPool {
    private final List<Backend> all;
    private volatile RoutingStrategy strategy;

    public BackendPool(List<Backend> backends, String algorithm) {
        this.all = List.copyOf(backends);
        this.strategy = Strategies.create(algorithm);
    }

    public List<Backend> all() { return all; }
    public RoutingStrategy strategy() { return strategy; }
    public void setAlgorithm(String name) { this.strategy = Strategies.create(name); }

    /** Picks a healthy backend not in {@code exclude}; null if none available. */
    public Backend select(String clientKey, Set<Backend> exclude) {
        List<Backend> candidates = new ArrayList<>(all.size());
        for (Backend b : all) if (b.healthy() && !exclude.contains(b)) candidates.add(b);
        if (candidates.isEmpty()) return null;
        return strategy.select(candidates, clientKey);
    }

    public boolean anyHealthy() { for (Backend b : all) if (b.healthy()) return true; return false; }
}
