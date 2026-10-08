package lb.bench;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntFunction;
import java.util.regex.*;

/**
 * Load generator + analyser. For each (algorithm x scenario) it fires N requests at the given concurrency and reports
 * throughput, latency percentiles, 429 count, per-backend distribution and Jain's fairness index (1.0 = perfectly even).
 *
 * Usage: Benchmark --url http://localhost:8080/ --admin http://localhost:9100 [--requests 5000] [--concurrency 50]
 *        [--clients 200] [--weights backend-1=3,backend-2=1] [--algorithms round_robin,least_connections] [--scenarios uniform,hotspot] [--csv results.csv]
 */
public final class Benchmark {
    record Result(String algo, String scenario, int total, int ok, int throttled, int failed, double seconds,
                  double p50, double p95, double p99, Map<String, Long> perBackend, double jain) {}

    public static void main(String[] a) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i + 1 < a.length; i += 2)
            o.put(a[i].replaceFirst("^--", ""), a[i + 1]);

        String url = o.getOrDefault("url", "http://localhost:8080/");
        String admin = o.get("admin");
        int n = Integer.parseInt(o.getOrDefault("requests", "5000"));
        int conc = Integer.parseInt(o.getOrDefault("concurrency", "50"));
        int clients = Integer.parseInt(o.getOrDefault("clients", "200"));

        List<String> algos = admin == null
            ? List.of("(current)")
            : Arrays.asList(o.getOrDefault(
                "algorithms",
                "round_robin,weighted_round_robin,least_connections,consistent_hash"
            ).split(","));

        List<String> scenarios = Arrays.asList(
            o.getOrDefault("scenarios", "uniform,hotspot").split(",")
        );

        String csv = o.getOrDefault("csv", "benchmark-results.csv");

        Map<String, Double> weights = new HashMap<>();

        // Optional: makes fairness weight-aware
        // (share proportional to weight = 1.0)
        if (o.containsKey("weights")) {
            for (String kv : o.get("weights").split(",")) {
                weights.put(
                    kv.split("=")[0],
                    Double.parseDouble(kv.split("=")[1])
                );
            }
        }

        HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

        int expectedBackends = admin == null
            ? 0
            : count(
                get(http, admin + "/admin/backends"),
                "\"id\""
            );

        List<Result> results = new ArrayList<>();

        for (String algo : algos) {
            if (admin != null) {
                System.out.println(
                    "-> algorithm set: "
                    + get(
                        http,
                        admin + "/admin/algorithm?name=" + algo
                    )
                );
            }

            for (String sc : scenarios) {
                run(
                    http,
                    url,
                    200,
                    conc,
                    i -> "warmup-" + (i % 20),
                    expectedBackends,
                    weights
                );

                IntFunction<String> pick = sc.equals("hotspot")
                    ? i -> (i % 10 < 8)
                        ? "hot-" + (i % 5)
                        : "client-" + (i % clients)
                    : i -> "client-" + (i % clients);

                Result r = run(
                    http,
                    url,
                    n,
                    conc,
                    pick,
                    expectedBackends,
                    weights
                );

                results.add(
                    new Result(
                        algo,
                        sc,
                        r.total,
                        r.ok,
                        r.throttled,
                        r.failed,
                        r.seconds,
                        r.p50,
                        r.p95,
                        r.p99,
                        r.perBackend,
                        r.jain
                    )
                );
            }
        }

        print(results);
        writeCsv(results, csv);
        System.out.println("CSV written to " + csv);
        System.exit(0);
    }

    static Result run(
        HttpClient http,
        String url,
        int n,
        int conc,
        IntFunction<String> clientFor,
        int expectedBackends,
        Map<String, Double> weights
    ) throws Exception {

        long[] lat = new long[n];
        Arrays.fill(lat, -1);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger throttled = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        ConcurrentHashMap<String, LongAdder> per =
            new ConcurrentHashMap<>();

        Semaphore sem = new Semaphore(conc);

        ExecutorService ex =
            Executors.newVirtualThreadPerTaskExecutor();

        long t0 = System.nanoTime();

        for (int i = 0; i < n; i++) {
            sem.acquire();

            final int idx = i;

            ex.submit(() -> {
                try {
                    HttpRequest req =
                        HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .header(
                                "X-Client-Id",
                                clientFor.apply(idx)
                            )
                            .GET()
                            .build();

                    long s = System.nanoTime();

                    HttpResponse<Void> r =
                        http.send(
                            req,
                            HttpResponse.BodyHandlers.discarding()
                        );

                    long d = System.nanoTime() - s;

                    if (r.statusCode() == 200) {
                        lat[idx] = d;
                        ok.incrementAndGet();

                        per.computeIfAbsent(
                            r.headers()
                                .firstValue("X-Backend")
                                .orElse("unknown"),
                            k -> new LongAdder()
                        ).increment();

                    } else if (r.statusCode() == 429) {
                        throttled.incrementAndGet();

                    } else {
                        failed.incrementAndGet();
                    }

                } catch (Exception e) {
                    failed.incrementAndGet();

                    System.err.println(
                        "REQUEST " + idx + " FAILED: "
                        + e.getClass().getName()
                        + ": "
                        + e.getMessage()
                    );

                } finally {
                    sem.release();
                }
            });
        }

        ex.shutdown();

        boolean finished =
            ex.awaitTermination(
                10,
                TimeUnit.MINUTES
            );

        if (!finished) {
            System.err.println(
                "WARNING: benchmark executor did not terminate within 10 minutes"
            );
        }

        double secs =
            (System.nanoTime() - t0) / 1e9;

        long[] good =
            Arrays.stream(lat)
                .filter(v -> v >= 0)
                .sorted()
                .toArray();

        Map<String, Long> dist =
            new TreeMap<>();

        per.forEach(
            (k, v) -> dist.put(k, v.sum())
        );

        return new Result(
            "",
            "",
            n,
            ok.get(),
            throttled.get(),
            failed.get(),
            secs,
            pct(good, 0.50),
            pct(good, 0.95),
            pct(good, 0.99),
            dist,
            jain(
                dist,
                weights,
                expectedBackends
            )
        );
    }

    static double pct(long[] sorted, double p) {
        if (sorted.length == 0)
            return Double.NaN;

        return sorted[
            (int) Math.min(
                sorted.length - 1,
                Math.ceil(p * sorted.length) - 1
            )
        ] / 1e6;
    }

    /** Jain's fairness index (1.0 = perfectly fair) over load normalised by weight; idle backends count as 0. */
    static double jain(
        Map<String, Long> dist,
        Map<String, Double> weights,
        int expectedBackends
    ) {
        Set<String> names =
            new TreeSet<>(dist.keySet());

        names.addAll(weights.keySet());

        double sum = 0;
        double sq = 0;

        for (String nme : names) {
            double x =
                dist.getOrDefault(nme, 0L)
                    / weights.getOrDefault(nme, 1.0);

            sum += x;
            sq += x * x;
        }

        int n =
            Math.max(
                expectedBackends,
                names.size()
            );

        return sq == 0
            ? 0
            : sum * sum / (n * sq);
    }

    static void print(List<Result> rs) {
        System.out.printf(
            "%n%-22s %-9s %7s %7s %6s %9s %8s %8s %8s %6s  %s%n",
            "algorithm",
            "scenario",
            "ok",
            "429",
            "fail",
            "req/s",
            "p50ms",
            "p95ms",
            "p99ms",
            "jain",
            "distribution"
        );

        for (Result r : rs) {
            System.out.printf(
                "%-22s %-9s %7d %7d %6d %9.0f %8.2f %8.2f %8.2f %6.3f  %s%n",
                r.algo,
                r.scenario,
                r.ok,
                r.throttled,
                r.failed,
                r.ok / r.seconds,
                r.p50,
                r.p95,
                r.p99,
                r.jain,
                r.perBackend
            );
        }
    }

    static void writeCsv(
        List<Result> rs,
        String path
    ) throws IOException {

        try (
            PrintWriter w =
                new PrintWriter(
                    Files.newBufferedWriter(
                        Path.of(path)
                    )
                )
        ) {
            w.println(
                "algorithm,scenario,ok,throttled,failed,seconds,"
                + "req_per_sec,p50_ms,p95_ms,p99_ms,"
                + "jain_fairness,distribution"
            );

            for (Result r : rs) {
                w.printf(
                    Locale.ROOT,
                    "%s,%s,%d,%d,%d,%.3f,%.1f,%.3f,%.3f,%.3f,%.4f,\"%s\"%n",
                    r.algo,
                    r.scenario,
                    r.ok,
                    r.throttled,
                    r.failed,
                    r.seconds,
                    r.ok / r.seconds,
                    r.p50,
                    r.p95,
                    r.p99,
                    r.jain,
                    r.perBackend
                );
            }
        }
    }

    static String get(
        HttpClient http,
        String u
    ) throws Exception {

        return http.send(
            HttpRequest.newBuilder(
                URI.create(u)
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body();
    }

    static int count(
        String s,
        String needle
    ) {
        Matcher m =
            Pattern.compile(
                Pattern.quote(needle)
            ).matcher(s);

        int c = 0;

        while (m.find())
            c++;

        return c;
    }
}