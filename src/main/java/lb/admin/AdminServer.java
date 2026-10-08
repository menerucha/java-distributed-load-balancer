package lb.admin;

import com.sun.net.httpserver.*;
import lb.algo.Strategies;
import lb.config.Config;
import lb.core.*;
import lb.metrics.Metrics;
import lb.ratelimit.RateLimiter;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/** Admin/observability endpoint: /metrics (Prometheus), /health, /admin/backends, /admin/algorithm?name=... */
public final class AdminServer {
    private final HttpServer http;

    public AdminServer(int port, Config cfg, BackendPool pool, RateLimiter limiter, Metrics metrics) throws IOException {
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        http.createContext("/metrics", ex -> send(ex, 200, "text/plain; version=0.0.4; charset=utf-8", metrics.render(pool, limiter, cfg.mode)));

        http.createContext("/health", ex -> {
            long up = pool.all().stream().filter(Backend::healthy).count();
            boolean ok = up > 0;
            send(ex, ok ? 200 : 503, "application/json",
                 "{\"status\":\"" + (ok ? "UP" : "DOWN") + "\",\"healthy_backends\":" + up + ",\"total_backends\":" + pool.all().size() + "}");
        });

        http.createContext("/admin/backends", ex -> {
            StringJoiner j = new StringJoiner(",", "[", "]");
            for (Backend b : pool.all())
                j.add("{\"id\":\"" + b.id() + "\",\"weight\":" + b.weight + ",\"healthy\":" + b.healthy()
                      + ",\"active\":" + b.active() + ",\"requests\":" + b.requests.sum() + ",\"errors\":" + b.errors.sum() + "}");
            send(ex, 200, "application/json", j.toString());
        });

        http.createContext("/admin/algorithm", ex -> {
            String q = ex.getRequestURI().getQuery();
            String name = null;
            if (q != null) for (String kv : q.split("&")) if (kv.startsWith("name=")) name = URLDecoder.decode(kv.substring(5), StandardCharsets.UTF_8);
            if (name != null) {
                try { pool.setAlgorithm(name); }
                catch (IllegalArgumentException e) { send(ex, 400, "application/json", "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}"); return; }
            }
            send(ex, 200, "application/json", "{\"algorithm\":\"" + pool.strategy().name() + "\",\"available\":" + toJson(Strategies.names()) + "}");
        });
    }

    private static String toJson(Collection<String> c) {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (String s : c) j.add("\"" + s + "\"");
        return j.toString();
    }

private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", type);
    ex.getResponseHeaders().set("Connection", "close");
    ex.sendResponseHeaders(code, b.length);
    try (OutputStream os = ex.getResponseBody()) { os.write(b); }
}

    public void start() { http.start(); }
    public void stop() { http.stop(0); }
    public int port() { return http.getAddress().getPort(); }
}
