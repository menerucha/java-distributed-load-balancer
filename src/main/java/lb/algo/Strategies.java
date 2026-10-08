package lb.algo;

import java.util.*;
import java.util.function.Supplier;

/** Registry/factory for routing algorithms. */
public final class Strategies {
    private static final Map<String, Supplier<RoutingStrategy>> REGISTRY = new LinkedHashMap<>();
    static {
        register("round_robin", RoundRobin::new);
        register("weighted_round_robin", WeightedRoundRobin::new);
        register("least_connections", LeastConnections::new);
        register("consistent_hash", ConsistentHash::new);
    }
    private Strategies() {}
    public static void register(String name, Supplier<RoutingStrategy> s) { REGISTRY.put(name, s); }
    public static Set<String> names() { return REGISTRY.keySet(); }
    public static RoutingStrategy create(String name) {
        Supplier<RoutingStrategy> s = REGISTRY.get(name == null ? "" : name.toLowerCase());
        if (s == null) throw new IllegalArgumentException("Unknown algorithm '" + name + "'. Available: " + names());
        return s.get();
    }
}
