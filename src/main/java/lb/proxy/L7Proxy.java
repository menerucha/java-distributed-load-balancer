package lb.proxy;

import lb.config.Config;
import lb.core.*;
import lb.metrics.Metrics;
import lb.ratelimit.RateLimiter;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Layer-7 (HTTP/1.x) reverse proxy. Parses each request, rate-limits per client (X-Client-Id header, else IP),
 * routes with the active algorithm, forwards with X-Forwarded-For, and relays the response.
 * One request per client connection ("Connection: close" in both directions) keeps the proxy simple and robust.
 */
public final class L7Proxy implements Proxy {
    private static final Logger LOG = Logger.getLogger("lb.l7");
    private static final int MAX_HEAD = 16 * 1024, MAX_BODY = 10 * 1024 * 1024;
    private final Config cfg; private final BackendPool pool; private final RateLimiter limiter; private final Metrics metrics;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private ServerSocket server;

    public L7Proxy(Config cfg, BackendPool pool, RateLimiter limiter, Metrics metrics) {
        this.cfg = cfg; this.pool = pool; this.limiter = limiter; this.metrics = metrics;
    }

    @Override public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(cfg.port), 1024);
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try { Socket s = server.accept(); exec.submit(() -> handle(s)); }
                catch (IOException e) { if (!server.isClosed()) LOG.warning("accept failed: " + e); }
            }
        }, "l7-acceptor");
        t.setDaemon(true); t.start();
        LOG.info("L7 proxy listening on :" + port());
    }

    @Override public int port() { return server.getLocalPort(); }

    @Override public void stop() {
        try { server.close(); } catch (IOException ignored) {}
        exec.shutdownNow();
    }

    private void handle(Socket client) {
        String ip = ((InetSocketAddress) client.getRemoteSocketAddress()).getAddress().getHostAddress();
        metrics.connections.increment();
        try (client) {
            client.setSoTimeout(cfg.idleTimeoutMs);
            client.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(client.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), 16384);

            Http.Head req;
            try { req = Http.readHead(in, MAX_HEAD); }
            catch (IOException e) { reply(out, 400, "Bad Request", "Malformed request\n"); return; }
            if (req == null) return;

            if (req.get("Transfer-Encoding") != null) { reply(out, 501, "Not Implemented", "Chunked request bodies are not supported\n"); return; }
            int len = 0;
            String cl = req.get("Content-Length");
            if (cl != null) {
                try { len = Integer.parseInt(cl.trim()); } catch (NumberFormatException e) { reply(out, 400, "Bad Request", "Bad Content-Length\n"); return; }
                if (len < 0 || len > MAX_BODY) { reply(out, 413, "Payload Too Large", "Body too large\n"); return; }
            }
            byte[] body = len > 0 ? in.readNBytes(len) : new byte[0];
            metrics.bytesIn.add(body.length + 0L);

            String clientId = req.get("X-Client-Id");
            String key = clientId != null && !clientId.isBlank() ? clientId : ip;

            if (!limiter.allow(key)) {
                metrics.rateLimited.increment();
                reply(out, 429, "Too Many Requests", "Rate limit exceeded\n", "Retry-After", String.valueOf(limiter.retryAfterSeconds(key)));
                return;
            }

            // Prepare upstream request head
            String xff = req.get("X-Forwarded-For");
            req.remove("Connection", "Proxy-Connection", "Keep-Alive", "Expect", "X-Forwarded-For");
            req.set("Connection", "close");
            req.set("X-Forwarded-For", xff == null ? ip : xff + ", " + ip);
            byte[] reqHead = req.toBytes();

            // Connect with failover across up to 3 backends
            Set<Backend> tried = new HashSet<>();
            Socket up = null; Backend b = null;
            for (int attempt = 0; attempt < 3 && up == null; attempt++) {
                b = pool.select(key, tried);
                if (b == null) break;
                tried.add(b);
                Socket s = new Socket();
                try { s.connect(new InetSocketAddress(b.host, b.port), cfg.connectTimeoutMs); up = s; }
                catch (IOException e) { b.errors.increment(); b.setHealthy(false); L4Proxy.closeQuietly(s); }
            }
            if (up == null) {
                metrics.noBackend.increment();
                reply(out, 503, "Service Unavailable", "No healthy backend available\n");
                return;
            }

            final Backend chosen = b; final Socket upstream = up;
            chosen.acquire(); metrics.inFlight.incrementAndGet();
            long t0 = System.nanoTime();
            boolean responded = false;
            try (upstream) {
                upstream.setSoTimeout(cfg.idleTimeoutMs);
                upstream.setTcpNoDelay(true);
                OutputStream uout = upstream.getOutputStream();
                uout.write(reqHead); uout.write(body); uout.flush();

                InputStream uin = new BufferedInputStream(upstream.getInputStream(), 16384);
                Http.Head resp = Http.readHead(uin, MAX_HEAD);
                if (resp == null) throw new IOException("backend closed without responding");
                int status = parseStatus(resp.startLine());
                resp.remove("Connection", "Keep-Alive");
                resp.set("Connection", "close");
                byte[] rh = resp.toBytes();
                out.write(rh);
                responded = true;
                metrics.bytesOut.add(rh.length);
                byte[] buf = new byte[16384]; int n;
                while ((n = uin.read(buf)) != -1) { out.write(buf, 0, n); metrics.bytesOut.add(n); }
                out.flush();
                metrics.response(status);
            } catch (IOException e) {
                chosen.errors.increment();
                LOG.fine("proxy error via " + chosen.id() + ": " + e);
                if (!responded) {
                    metrics.response(502);
                    try { reply(out, 502, "Bad Gateway", "Upstream error\n"); } catch (IOException ignored) {}
                }
            } finally {
                chosen.release(); metrics.inFlight.decrementAndGet();
                metrics.observe(chosen.id(), (System.nanoTime() - t0) / 1e9);
            }
        } catch (IOException e) {
            LOG.fine("client error: " + e);
        }
    }

    private void reply(OutputStream out, int code, String reason, String body, String... extra) throws IOException {
        if (code != 502) metrics.response(code);
        Http.sendSimple(out, code, reason, body, extra);
    }

    private static int parseStatus(String startLine) {
        String[] p = startLine.split(" ");
        try { return p.length > 1 ? Integer.parseInt(p[1]) : 502; } catch (NumberFormatException e) { return 502; }
    }
}
