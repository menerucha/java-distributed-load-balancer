package lb.metrics;

import lb.config.Config;
import lb.core.*;
import lb.ratelimit.RateLimiter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Minimal dependency-free Prometheus registry; render() emits the text exposition format (v0.0.4). */
public final class Metrics {
    private static final double[] BOUNDS = {0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};

    static final class Histogram {
        final LongAdder[] counts = new LongAdder[BOUNDS.length + 1];
        final DoubleAdder sum = new DoubleAdder();
        final LongAdder count = new LongAdder();
        Histogram() { for (int i = 0; i < counts.length; i++) counts[i] = new LongAdder(); }
        void observe(double v) {
            int i = 0;
            while (i < BOUNDS.length && v > BOUNDS[i]) i++;
            counts[i].increment(); sum.add(v); count.increment();
        }
    }

    private final long startMs = System.currentTimeMillis();
    public final LongAdder connections = new LongAdder();
    public final LongAdder rateLimited = new LongAdder();
    public final LongAdder noBackend = new LongAdder();
    public final LongAdder bytesIn = new LongAdder();    // client -> backend
    public final LongAdder bytesOut = new LongAdder();   // backend -> client
    public final AtomicInteger inFlight = new AtomicInteger();
    private final ConcurrentHashMap<Integer, LongAdder> responses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Histogram> latency = new ConcurrentHashMap<>();

    public void response(int status) { responses.computeIfAbsent(status, k -> new LongAdder()).increment(); }

    public void observe(String backendId, double seconds) {
        latency.computeIfAbsent(backendId, k -> new Histogram()).observe(seconds);
    }

    public String render(BackendPool pool, RateLimiter limiter, Config.Mode mode) {
        StringBuilder sb = new StringBuilder(4096);
        simple(sb, "lb_connections_total", "counter", "Client connections accepted.", connections.sum());
        simple(sb, "lb_rate_limited_total", "counter", "Requests/connections rejected by the rate limiter.", rateLimited.sum());
        simple(sb, "lb_no_backend_available_total", "counter", "Requests that could not be routed (no healthy backend).", noBackend.sum());
        simple(sb, "lb_bytes_received_total", "counter", "Bytes received from clients and forwarded upstream.", bytesIn.sum());
        simple(sb, "lb_bytes_sent_total", "counter", "Bytes received from backends and forwarded to clients.", bytesOut.sum());
        simple(sb, "lb_requests_in_flight", "gauge", "Requests currently being proxied.", inFlight.get());
        simple(sb, "lb_rate_limiter_tracked_clients", "gauge", "Clients with a live token bucket.", limiter.trackedClients());
        simple(sb, "lb_uptime_seconds", "gauge", "Seconds since the load balancer started.", (System.currentTimeMillis() - startMs) / 1000.0);

        sb.append("# HELP lb_info Static info: active algorithm and proxy mode.\n# TYPE lb_info gauge\n");
        sb.append("lb_info{algorithm=\"").append(pool.strategy().name()).append("\",mode=\"").append(mode).append("\"} 1\n");

        perBackend(sb, pool, "lb_backend_requests_total", "counter", "Requests routed to each backend.", b -> b.requests.sum());
        perBackend(sb, pool, "lb_backend_errors_total", "counter", "Connection/IO errors per backend.", b -> b.errors.sum());
        perBackend(sb, pool, "lb_backend_active_connections", "gauge", "Active connections per backend.", b -> b.active());
        perBackend(sb, pool, "lb_backend_up", "gauge", "1 if the backend passes health checks.", b -> b.healthy() ? 1 : 0);
        perBackend(sb, pool, "lb_backend_weight", "gauge", "Configured weight per backend.", b -> b.weight);

        sb.append("# HELP lb_http_responses_total Responses returned to clients by status code (L7 mode).\n# TYPE lb_http_responses_total counter\n");
        new TreeMap<>(responses).forEach((code, c) ->
            sb.append("lb_http_responses_total{code=\"").append(code).append("\"} ").append(c.sum()).append('\n'));

        sb.append("# HELP lb_request_duration_seconds Time to proxy a request/connection, per backend.\n# TYPE lb_request_duration_seconds histogram\n");
        new TreeMap<>(latency).forEach((id, h) -> {
            long cum = 0;
            for (int i = 0; i < BOUNDS.length; i++) {
                cum += h.counts[i].sum();
                sb.append("lb_request_duration_seconds_bucket{backend=\"").append(id).append("\",le=\"").append(BOUNDS[i]).append("\"} ").append(cum).append('\n');
            }
            cum += h.counts[BOUNDS.length].sum();
            sb.append("lb_request_duration_seconds_bucket{backend=\"").append(id).append("\",le=\"+Inf\"} ").append(cum).append('\n');
            sb.append("lb_request_duration_seconds_sum{backend=\"").append(id).append("\"} ").append(h.sum.sum()).append('\n');
            sb.append("lb_request_duration_seconds_count{backend=\"").append(id).append("\"} ").append(h.count.sum()).append('\n');
        });
        return sb.toString();
    }

    private static void simple(StringBuilder sb, String name, String type, String help, Number v) {
        sb.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name).append(' ').append(type).append('\n');
        sb.append(name).append(' ').append(v).append('\n');
    }

    private static void perBackend(StringBuilder sb, BackendPool pool, String name, String type, String help,
                                   java.util.function.ToDoubleFunction<Backend> f) {
        sb.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name).append(' ').append(type).append('\n');
        for (Backend b : pool.all()) {
            double v = f.applyAsDouble(b);
            sb.append(name).append("{backend=\"").append(b.id()).append("\"} ")
              .append(v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)).append('\n');
        }
    }
}
