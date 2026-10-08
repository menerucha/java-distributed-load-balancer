package lb.algo;

import lb.core.Backend;
import java.util.*;

/** Smooth weighted round robin (nginx-style): interleaves picks instead of bursting per backend. */
public final class WeightedRoundRobin implements RoutingStrategy {
    private final Map<Backend, Integer> current = new HashMap<>();
    @Override public String name() { return "weighted_round_robin"; }

    @Override public synchronized Backend select(List<Backend> healthy, String clientKey) {
        Backend best = null; int bestCw = Integer.MIN_VALUE, total = 0;
        for (Backend b : healthy) {
            int cw = current.merge(b, b.weight, Integer::sum);
            total += b.weight;
            if (cw > bestCw) { best = b; bestCw = cw; }
        }
        current.merge(best, -total, Integer::sum);
        return best;
    }
}
