package lb.algo;

import lb.core.Backend;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class RoundRobin implements RoutingStrategy {
    private final AtomicLong counter = new AtomicLong();
    @Override public String name() { return "round_robin"; }
    @Override public Backend select(List<Backend> healthy, String clientKey) {
        return healthy.get((int) Math.floorMod(counter.getAndIncrement(), (long) healthy.size()));
    }
}
