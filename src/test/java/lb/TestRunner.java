package lb;

import com.sun.net.httpserver.HttpServer;
import lb.algo.*;
import lb.config.Config;
import lb.core.Backend;
import lb.demo.DemoBackend;
import lb.ratelimit.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Dependency-free test runner (no JUnit needed). Exit code 1 on any failure. */
public final class TestRunner {
    interface T { void run() throws Exception; }
    static int passed, failed;

    static void test(String name, T t) {
        try { t.run(); passed++; System.out.println("  PASS  " + name); }
        catch (Throwable e) { failed++; System.out.println("  FAIL  " + name + "  -> " + e); }
    }
    static void check(boolean c, String msg) { if (!c) throw new AssertionError(msg); }
    static void eq(Object a, Object b, String msg) { if (!Objects.equals(a, b)) throw new AssertionError(msg + " expected=" + b + " actual=" + a); }

    // ---------------------------------------------------------------- helpers
    static List<Backend> backends(int... weights) {
        List<Backend> l = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) l.add(new Backend("h" + i, 8000 + i, weights[i]));
        return l;
    }

    record Resp(int status, Map<String, String> h, String body) {}

    static Resp raw(int port, String method, String path, Map<String, String> hdrs, byte[] body) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(8000);
            StringBuilder sb = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: test\r\nConnection: close\r\n");
            hdrs.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
            if (body != null) sb.append("Content-Length: ").append(body.length).append("\r\n");
            sb.append("\r\n");
            OutputStream o = s.getOutputStream();
            o.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) o.write(body);
            o.flush();
            String all = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            int split = all.indexOf("\r\n\r\n");
            String[] lines = all.substring(0, split).split("\r\n");
            Map<String, String> h = new HashMap<>();
            for (int i = 1; i < lines.length; i++) { int c = lines[i].indexOf(':'); h.put(lines[i].substring(0, c).toLowerCase(), lines[i].substring(c + 1).trim()); }
            return new Resp(Integer.parseInt(lines[0].split(" ")[1]), h, all.substring(split + 4));
        }
    }
    static Resp get(int port, String client) throws IOException { return raw(port, "GET", "/", client == null ? Map.of() : Map.of("X-Client-Id", client), null); }

    static String http(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    static int freePort() throws IOException { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }

    static Config cfg(Config.Mode mode, String algo, List<Integer> ports, boolean rl) {
        Config c = new Config();
        c.mode = mode; c.port = 0; c.adminPort = 0; c.algorithm = algo; c.rateLimitEnabled = rl; c.healthIntervalMs = 200;
        for (int p : ports) c.backends.add("127.0.0.1:" + p);
        return c;
    }

    static Map<String, Integer> distribution(int port, int n, String clientPrefix) throws IOException {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            Resp r = get(port, clientPrefix == null ? null : clientPrefix + i);
            check(r.status == 200, "expected 200 got " + r.status);
            m.merge(r.h.get("x-backend"), 1, Integer::sum);
        }
        return m;
    }

    // ---------------------------------------------------------------- unit tests
    static void unitTests() {
        System.out.println("\n== Routing algorithms ==");
        test("round robin cycles evenly", () -> {
            var bs = backends(1, 1, 1); var rr = new RoundRobin(); int[] c = new int[3];
            for (int i = 0; i < 300; i++) c[bs.indexOf(rr.select(bs, "x"))]++;
            check(c[0] == 100 && c[1] == 100 && c[2] == 100, Arrays.toString(c));
        });
        test("round robin adapts when backend list shrinks", () -> {
            var bs = backends(1, 1, 1); var rr = new RoundRobin();
            for (int i = 0; i < 10; i++) rr.select(bs, "x");
            var two = bs.subList(0, 2);
            for (int i = 0; i < 50; i++) check(two.contains(rr.select(two, "x")), "picked excluded backend");
        });
        test("weighted round robin honours weights exactly (5:1:1)", () -> {
            var bs = backends(5, 1, 1); var w = new WeightedRoundRobin(); int[] c = new int[3];
            for (int i = 0; i < 700; i++) c[bs.indexOf(w.select(bs, "x"))]++;
            check(c[0] == 500 && c[1] == 100 && c[2] == 100, Arrays.toString(c));
        });
        test("weighted round robin interleaves (no long bursts)", () -> {
            var bs = backends(5, 1, 1); var w = new WeightedRoundRobin(); int run = 0, maxRun = 0; Backend prev = null;
            for (int i = 0; i < 70; i++) { Backend b = w.select(bs, "x"); run = b == prev ? run + 1 : 1; maxRun = Math.max(maxRun, run); prev = b; }
            check(maxRun <= 5, "max consecutive picks of same backend = " + maxRun);
        });
        test("least connections picks the least loaded", () -> {
            var bs = backends(1, 1, 1); var lc = new LeastConnections();
            for (int i = 0; i < 5; i++) bs.get(0).acquire();
            for (int i = 0; i < 2; i++) bs.get(1).acquire();
            eq(lc.select(bs, "x"), bs.get(2), "should pick idle backend");
            bs.get(2).acquire(); bs.get(2).acquire(); bs.get(2).acquire();
            eq(lc.select(bs, "x"), bs.get(1), "should now pick backend 1");
        });
        test("least connections accounts for weight", () -> {
            var bs = backends(4, 1); var lc = new LeastConnections();
            for (int i = 0; i < 3; i++) bs.get(0).acquire();   // 3/4 = .75
            bs.get(1).acquire();                               // 1/1 = 1.0
            eq(lc.select(bs, "x"), bs.get(0), "heavier backend should still win");
        });
        test("consistent hash is sticky per key", () -> {
            var bs = backends(1, 1, 1, 1, 1); var ch = new ConsistentHash();
            for (int k = 0; k < 500; k++) { Backend f = ch.select(bs, "k" + k); for (int j = 0; j < 5; j++) eq(ch.select(bs, "k" + k), f, "key moved"); }
        });
        test("consistent hash spreads keys roughly evenly", () -> {
            var bs = backends(1, 1, 1, 1, 1); var ch = new ConsistentHash(); int[] c = new int[5];
            for (int k = 0; k < 20000; k++) c[bs.indexOf(ch.select(bs, "user-" + k))]++;
            for (int x : c) check(x > 3000 && x < 5000, "uneven: " + Arrays.toString(c));
        });
        test("consistent hash: removing a node only remaps that node's keys", () -> {
            var bs = backends(1, 1, 1, 1, 1); var ch = new ConsistentHash(); int n = 10000;
            Backend[] before = new Backend[n];
            for (int k = 0; k < n; k++) before[k] = ch.select(bs, "key" + k);
            var fewer = new ArrayList<>(bs); fewer.remove(2);
            int moved = 0, wronglyMoved = 0;
            for (int k = 0; k < n; k++) {
                Backend after = ch.select(fewer, "key" + k);
                if (after != before[k]) { moved++; if (before[k] != bs.get(2)) wronglyMoved++; }
            }
            check(wronglyMoved == 0, wronglyMoved + " keys moved that did not belong to the removed node");
            check(moved > 1000 && moved < 3000, "unexpected number moved: " + moved);
        });
        test("consistent hash respects weights", () -> {
            var bs = backends(3, 1); var ch = new ConsistentHash(); int[] c = new int[2];
            for (int k = 0; k < 20000; k++) c[bs.indexOf(ch.select(bs, "u" + k))]++;
            double ratio = (double) c[0] / c[1];
            check(ratio > 2.2 && ratio < 4.0, "ratio=" + ratio);
        });
        test("strategy registry rejects unknown names, is extensible", () -> {
            try { Strategies.create("nope"); throw new AssertionError("no exception"); } catch (IllegalArgumentException expected) {}
            Strategies.register("always_first", () -> new RoutingStrategy() {
                public String name() { return "always_first"; }
                public Backend select(List<Backend> h, String k) { return h.get(0); } });
            eq(Strategies.create("always_first").select(backends(1, 1), "x").port, 8000, "custom strategy");
        });

        System.out.println("\n== Token bucket rate limiter ==");
        test("allows burst up to capacity then rejects", () -> {
            long[] now = {0}; var tb = new TokenBucket(10, 5, () -> now[0]);
            for (int i = 0; i < 10; i++) check(tb.tryConsume(), "burst " + i);
            check(!tb.tryConsume(), "11th should be rejected");
        });
        test("refills at configured rate and caps at capacity", () -> {
            long[] now = {0}; var tb = new TokenBucket(10, 5, () -> now[0]);
            for (int i = 0; i < 10; i++) tb.tryConsume();
            now[0] += 1_000_000_000L;  // +1s => 5 tokens
            int ok = 0; for (int i = 0; i < 10; i++) if (tb.tryConsume()) ok++;
            eq(ok, 5, "tokens after 1s");
            now[0] += 60_000_000_000L; // long idle: capped
            ok = 0; for (int i = 0; i < 50; i++) if (tb.tryConsume()) ok++;
            eq(ok, 10, "capped at capacity");
        });
        test("1,500 simulated requests @150 rps for one client => ~capacity + rate*time", () -> {
            long[] now = {0}; var rl = new RateLimiter(true, 20, 100, null, () -> now[0]);
            int allowed = 0, total = 1500; long step = 1_000_000_000L / 150;
            for (int i = 0; i < total; i++) { now[0] += step; if (rl.allow("c1")) allowed++; }
            // 10s * 100/s + 20 burst = ~1020
            check(allowed >= 1000 && allowed <= 1040, "allowed=" + allowed);
        });
        test("clients are throttled independently (2,000 requests over 50 clients)", () -> {
            long[] now = {0}; var rl = new RateLimiter(true, 5, 1, null, () -> now[0]);
            int allowed = 0;
            for (int i = 0; i < 2000; i++) if (rl.allow("client-" + (i % 50))) allowed++;   // no time passes: 40 req each, 5 allowed
            eq(allowed, 250, "50 clients * burst 5");
            eq(rl.trackedClients(), 50, "tracked");
        });
        test("per-client overrides", () -> {
            long[] now = {0}; var rl = new RateLimiter(true, 2, 1, Config.parseOverrides("vip=100:50"), () -> now[0]);
            int vip = 0, normal = 0;
            for (int i = 0; i < 100; i++) { if (rl.allow("vip")) vip++; if (rl.allow("joe")) normal++; }
            eq(vip, 50, "vip burst"); eq(normal, 2, "normal burst");
        });
        test("disabled limiter allows everything; idle buckets are evicted", () -> {
            var off = new RateLimiter(false, 1, 1, null); for (int i = 0; i < 100; i++) check(off.allow("x"), "disabled");
            long[] now = {0}; var rl = new RateLimiter(true, 5, 1, null, () -> now[0]);
            rl.allow("a"); rl.allow("b"); now[0] += 10_000_000_000L; rl.allow("b");
            rl.evictIdle(5_000_000_000L);
            eq(rl.trackedClients(), 1, "only b remains");
        });
        test("thread-safe under contention (never exceeds burst when no time elapses)", () -> {
            var tb = new TokenBucket(1000, 0.000001); AtomicInteger ok = new AtomicInteger();
            ExecutorService ex = Executors.newFixedThreadPool(16);
            for (int i = 0; i < 5000; i++) ex.submit(() -> { if (tb.tryConsume()) ok.incrementAndGet(); });
            ex.shutdown(); ex.awaitTermination(10, TimeUnit.SECONDS);
            check(ok.get() >= 1000 && ok.get() <= 1001, "ok=" + ok.get());
        });
    }

    // ---------------------------------------------------------------- integration tests
    static void integrationTests() throws Exception {
        System.out.println("\n== End-to-end (real sockets) ==");
        List<HttpServer> servers = new ArrayList<>(); List<Integer> ports = new ArrayList<>();
        for (int i = 0; i < 3; i++) { int p = freePort(); ports.add(p); servers.add(DemoBackend.start(p, "b" + i, 0, 0)); }

        LoadBalancer l7 = new LoadBalancer(cfg(Config.Mode.L7, "round_robin", ports, false)); l7.start();
        int lp = l7.port(), ap = l7.adminPort();
        String admin = "http://127.0.0.1:" + ap;

        test("L7 round robin: 90 requests split 30/30/30", () -> {
            var d = distribution(lp, 90, "c");
            eq(d, Map.of("b0", 30, "b1", 30, "b2", 30), "distribution");
        });
        test("L7 forwards POST bodies and adds X-Forwarded-For", () -> {
            byte[] body = new byte[5000]; Arrays.fill(body, (byte) 'x');
            Resp r = raw(lp, "POST", "/upload", Map.of(), body);
            eq(r.status, 200, "status"); eq(r.h.get("x-body-length"), "5000", "body length seen by backend");
            check(r.h.get("x-seen-forwarded-for").contains("127.0.0.1"), "xff");
        });
        test("L7 concurrent load: 500 requests / 50 threads all succeed", () -> {
            ExecutorService ex = Executors.newFixedThreadPool(50); AtomicInteger ok = new AtomicInteger(); List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 500; i++) fs.add(ex.submit(() -> { try { if (get(lp, null).status == 200) ok.incrementAndGet(); } catch (IOException ignored) {} }));
            for (var f : fs) f.get(); ex.shutdown();
            eq(ok.get(), 500, "successful");
        });
        test("algorithm hot-swap via admin API (consistent_hash => sticky)", () -> {
            check(http(admin + "/admin/algorithm?name=consistent_hash").contains("consistent_hash"), "switch");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 25; i++) seen.add(get(lp, "sticky-user").h.get("x-backend"));
            eq(seen.size(), 1, "same client should hit one backend");
            Set<String> all = new HashSet<>();
            for (int i = 0; i < 60; i++) all.add(get(lp, "user-" + i).h.get("x-backend"));
            check(all.size() >= 2, "different clients should spread");
            try { http(admin + "/admin/algorithm?name=bogus"); throw new AssertionError("expected 400"); } catch (IOException expected) {}
            http(admin + "/admin/algorithm?name=round_robin");
        });
        test("failover: killed backend is skipped immediately and marked down", () -> {
            servers.get(1).stop(0);
            for (int i = 0; i < 40; i++) { Resp r = get(lp, null); eq(r.status, 200, "must retry on another backend"); check(!"b1".equals(r.h.get("x-backend")), "hit dead backend"); }
            Thread.sleep(500);
            check(http(admin + "/admin/backends").contains("\"healthy\":false"), "marked unhealthy");
            check(http(admin + "/health").contains("\"healthy_backends\":2"), "health endpoint");
        });
        test("recovery: backend is re-added after coming back", () -> {
            servers.set(1, DemoBackend.start(ports.get(1), "b1", 0, 0));
            Set<String> seen = new HashSet<>();
            for (int t = 0; t < 30 && !seen.contains("b1"); t++) { Thread.sleep(100); for (int i = 0; i < 6; i++) seen.add(get(lp, null).h.get("x-backend")); }
            check(seen.contains("b1"), "b1 never returned");
        });
        test("all backends down => 503, then recovers", () -> {
            for (var s : servers) s.stop(0);
            eq(get(lp, null).status, 503, "status");
            for (int i = 0; i < 3; i++) servers.set(i, DemoBackend.start(ports.get(i), "b" + i, 0, 0));
            Thread.sleep(600);
            eq(get(lp, null).status, 200, "recovered");
        });
        test("Prometheus endpoint exposes 10+ metric families in valid format", () -> {
            String m = http(admin + "/metrics");
            Set<String> fams = new TreeSet<>();
            for (String line : m.split("\n")) if (line.startsWith("# TYPE ")) fams.add(line.split(" ")[2]);
            check(fams.size() >= 10, "families=" + fams.size() + " " + fams);
            for (String need : List.of("lb_backend_requests_total", "lb_request_duration_seconds_bucket", "lb_backend_up", "lb_rate_limited_total",
                    "lb_backend_active_connections", "lb_http_responses_total", "lb_bytes_sent_total", "lb_info"))
                check(m.contains(need), "missing " + need);
            for (String line : m.split("\n")) if (!line.startsWith("#") && !line.isBlank())
                check(line.matches("^[a-zA-Z_:][a-zA-Z0-9_:]*(\\{[^}]*\\})? [-+0-9.eE]+(Inf)?$"), "bad line: " + line);
            System.out.println("        (" + fams.size() + " families: " + fams + ")");
        });
        l7.stop();

        test("L7 weighted round robin splits 6:3:1", () -> {
            Config c = cfg(Config.Mode.L7, "weighted_round_robin", ports, false);
            c.backends.clear(); for (int i = 0; i < 3; i++) c.backends.add("127.0.0.1:" + ports.get(i) + ":" + new int[]{6, 3, 1}[i]);
            LoadBalancer lb = new LoadBalancer(c); lb.start();
            try { eq(distribution(lb.port(), 100, null), Map.of("b0", 60, "b1", 30, "b2", 10), "weighted split"); } finally { lb.stop(); }
        });
        test("L7 rate limiter returns 429 + Retry-After; other clients unaffected", () -> {
            Config c = cfg(Config.Mode.L7, "round_robin", ports, true); c.rateLimitCapacity = 5; c.rateLimitRefillPerSec = 1;
            LoadBalancer lb = new LoadBalancer(c); lb.start();
            try {
                int ok = 0, throttled = 0; String retry = null;
                for (int i = 0; i < 20; i++) { Resp r = get(lb.port(), "greedy"); if (r.status == 200) ok++; else if (r.status == 429) { throttled++; retry = r.h.get("retry-after"); } }
                check(ok >= 5 && ok <= 7, "ok=" + ok); check(throttled >= 13, "throttled=" + throttled); check(retry != null, "Retry-After header");
                eq(get(lb.port(), "polite").status, 200, "other client");
                check(http("http://127.0.0.1:" + lb.adminPort() + "/metrics").contains("lb_rate_limited_total " + throttled), "metric matches");
            } finally { lb.stop(); }
        });
        test("L7 least_connections favours the faster backend under concurrency", () -> {
            int slowP = freePort(), fastP = freePort();
            HttpServer slow = DemoBackend.start(slowP, "slow", 120, 0), fast = DemoBackend.start(fastP, "fast", 0, 0);
            LoadBalancer lb = new LoadBalancer(cfg(Config.Mode.L7, "least_connections", List.of(slowP, fastP), false)); lb.start();
            try {
                ExecutorService ex = Executors.newFixedThreadPool(20); ConcurrentHashMap<String, AtomicInteger> c = new ConcurrentHashMap<>(); List<Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < 200; i++) fs.add(ex.submit(() -> { try { c.computeIfAbsent(get(lb.port(), null).h.get("x-backend"), k -> new AtomicInteger()).incrementAndGet(); } catch (IOException ignored) {} }));
                for (var f : fs) f.get(); ex.shutdown();
                check(c.get("fast").get() > c.get("slow").get() * 2, "fast=" + c.get("fast") + " slow=" + c.get("slow"));
            } finally { lb.stop(); slow.stop(0); fast.stop(0); }
        });
        test("L4 mode: pure TCP proxying distributes connections & carries HTTP", () -> {
            LoadBalancer lb = new LoadBalancer(cfg(Config.Mode.L4, "round_robin", ports, false)); lb.start();
            try {
                eq(distribution(lb.port(), 60, null), Map.of("b0", 20, "b1", 20, "b2", 20), "L4 distribution");
                String m = http("http://127.0.0.1:" + lb.adminPort() + "/metrics");
                check(m.contains("lb_info{algorithm=\"round_robin\",mode=\"L4\"}"), "mode label");
            } finally { lb.stop(); }
        });
        test("L4 mode: failover when a backend is down; rate limit drops connections", () -> {
            int dead = freePort();
            Config c = cfg(Config.Mode.L4, "round_robin", List.of(dead, ports.get(0)), true); c.rateLimitCapacity = 3; c.rateLimitRefillPerSec = 0.1;
            LoadBalancer lb = new LoadBalancer(c); lb.start();
            try {
                int ok = 0, dropped = 0;
                for (int i = 0; i < 10; i++) { try { if (get(lb.port(), null).status == 200) ok++; } catch (Exception e) { dropped++; } }
                eq(ok, 3, "burst allowed (failover to live backend)"); eq(dropped, 7, "rest dropped");
            } finally { lb.stop(); }
        });
        for (var s : servers) s.stop(0);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.config.file", "/dev/null");
        unitTests();
        integrationTests();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
