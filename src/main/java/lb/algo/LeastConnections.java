package lb.algo;

import lb.core.Backend;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Picks the backend with the lowest active/weight ratio; ties rotate to avoid favouring index 0. */
public final class LeastConnections implements RoutingStrategy {
    private final AtomicLong tie = new AtomicLong();
    @Override public String name() { return "least_connections"; }

    @Override public Backend select(List<Backend> healthy, String clientKey) {
        int n = healthy.size();
        int start = (int) Math.floorMod(tie.getAndIncrement(), (long) n);
        Backend best = null; double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            Backend b = healthy.get((start + i) % n);
            double score = (double) b.active() / b.weight;
            if (score < bestScore) { best = b; bestScore = score; }
        }
        return best;
    }
}
