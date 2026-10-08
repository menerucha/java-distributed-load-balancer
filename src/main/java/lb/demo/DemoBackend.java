package lb.demo;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Tiny HTTP backend used for demos/tests. Reports its identity via the X-Backend header. */
public final class DemoBackend {
    public static HttpServer start(int port, String name, int latencyMs, int jitterMs) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 512);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/", ex -> {
            try {
                long delay = latencyMs + (jitterMs > 0 ? ThreadLocalRandom.current().nextInt(jitterMs + 1) : 0);
                if (delay > 0) Thread.sleep(delay);
                byte[] in = ex.getRequestBody().readAllBytes();
                byte[] out = ("Hello from " + name + "\n").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("X-Backend", name);
                ex.getResponseHeaders().set("X-Body-Length", String.valueOf(in.length));
                String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
                if (xff != null) ex.getResponseHeaders().set("X-Seen-Forwarded-For", xff);
                ex.sendResponseHeaders(200, out.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(out); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        s.start();
        return s;
    }

    public static void main(String[] args) throws Exception {
        String name = System.getenv().getOrDefault("BACKEND_NAME", "backend");
        int port = Integer.parseInt(System.getenv().getOrDefault("BACKEND_PORT", "9001"));
        int latency = Integer.parseInt(System.getenv().getOrDefault("LATENCY_MS", "0"));
        int jitter = Integer.parseInt(System.getenv().getOrDefault("JITTER_MS", "0"));
        start(port, name, latency, jitter);
        System.out.println("Demo backend '" + name + "' on :" + port + " latency=" + latency + "ms jitter=" + jitter + "ms");
        Thread.currentThread().join();
    }
}
