package lb.algo;

import lb.core.Backend;
import java.util.List;

/** Extension point: implement this and register it in {@link Strategies} to add an algorithm. */
public interface RoutingStrategy {
    String name();
    /** @param healthy non-empty list of eligible backends; @param clientKey stable client identifier */
    Backend select(List<Backend> healthy, String clientKey);
}
