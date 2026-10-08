package lb.proxy;

import lb.config.Config;
import lb.core.*;
import lb.metrics.Metrics;
import lb.ratelimit.RateLimiter;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Layer-4 (TCP) proxy: picks a backend per connection and pipes bytes both ways without parsing the protocol.
 * Client key for routing / rate limiting is the client IP.
 */
public final class L4Proxy implements Proxy {
    private static final Logger LOG = Logger.getLogger("lb.l4");
    private final Config cfg; private final BackendPool pool; private final RateLimiter limiter; private final Metrics metrics;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private ServerSocket server;

    public L4Proxy(Config cfg, BackendPool pool, RateLimiter limiter, Metrics metrics) {
        this.cfg = cfg; this.pool = pool; this.limiter = limiter; this.metrics = metrics;
    }

    @Override public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(cfg.port));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try { Socket s = server.accept(); exec.submit(() -> handle(s)); }
                catch (IOException e) { if (!server.isClosed()) LOG.warning("accept failed: " + e); }
            }
        }, "l4-acceptor");
        t.setDaemon(true); t.start();
        LOG.info("L4 proxy listening on :" + port());
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
            if (!limiter.allow(ip)) { metrics.rateLimited.increment(); return; }

            Set<Backend> tried = new HashSet<>();
            Socket up = null; Backend b = null;
            for (int attempt = 0; attempt < 3 && up == null; attempt++) {
                b = pool.select(ip, tried);
                if (b == null) break;
                tried.add(b);
                Socket s = new Socket();
                try { s.connect(new InetSocketAddress(b.host, b.port), cfg.connectTimeoutMs); up = s; }
                catch (IOException e) { b.errors.increment(); b.setHealthy(false); closeQuietly(s); }
            }
            if (up == null) { metrics.noBackend.increment(); return; }

            final Backend chosen = b; final Socket upstream = up;
            upstream.setSoTimeout(cfg.idleTimeoutMs);
            upstream.setTcpNoDelay(true);
            chosen.acquire(); metrics.inFlight.incrementAndGet();
            long t0 = System.nanoTime();
            try (upstream) {
                Thread t = Thread.ofVirtual().start(() -> pump(client, upstream, metrics.bytesIn));
                pump(upstream, client, metrics.bytesOut);
                t.join();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally {
                chosen.release(); metrics.inFlight.decrementAndGet();
                metrics.observe(chosen.id(), (System.nanoTime() - t0) / 1e9);
            }
        } catch (IOException e) {
            LOG.fine("connection error: " + e);
        }
    }

    /** Copies src->dst until EOF, then half-closes dst. On error, closes both sides to unblock the peer pump. */
    private static void pump(Socket src, Socket dst, LongAdder counter) {
        byte[] buf = new byte[16384];
        try {
            InputStream in = src.getInputStream(); OutputStream out = dst.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); counter.add(n); }
            try { dst.shutdownOutput(); } catch (IOException ignored) {}
        } catch (IOException e) {
            closeQuietly(src); closeQuietly(dst);
        }
    }

    static void closeQuietly(Socket s) { try { s.close(); } catch (IOException ignored) {} }
}
