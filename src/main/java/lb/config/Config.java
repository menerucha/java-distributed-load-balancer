package lb.config;

import java.util.*;

/** All runtime configuration. Populated from environment variables (see fromEnv()). */
public class Config {
    public enum Mode { L4, L7 }

    public Mode mode = Mode.L7;
    public int port = 8080;
    public int adminPort = 9100;
    public String algorithm = "round_robin";
    /** Entries of the form host:port[:weight] */
    public List<String> backends = new ArrayList<>();
    public boolean rateLimitEnabled = true;
    public double rateLimitCapacity = 100;       // burst size (tokens)
    public double rateLimitRefillPerSec = 50;    // sustained rate (tokens / second)
    /** Per-client overrides: clientKey -> {refillPerSec, capacity} */
    public Map<String, double[]> rateLimitOverrides = new HashMap<>();
    public int healthIntervalMs = 2000;
    public int connectTimeoutMs = 2000;
    public int idleTimeoutMs = 30000;

    public static Config fromEnv() {
        Config c = new Config();
        c.mode = Mode.valueOf(env("LB_MODE", "L7").toUpperCase());
        c.port = Integer.parseInt(env("LB_PORT", "8080"));
        c.adminPort = Integer.parseInt(env("LB_ADMIN_PORT", "9100"));
        c.algorithm = env("LB_ALGORITHM", "round_robin");
        for (String s : env("LB_BACKENDS", "localhost:9001:1,localhost:9002:1,localhost:9003:1").split(","))
            if (!s.isBlank()) c.backends.add(s.trim());
        c.rateLimitEnabled = Boolean.parseBoolean(env("LB_RATE_LIMIT_ENABLED", "true"));
        c.rateLimitCapacity = Double.parseDouble(env("LB_RATE_LIMIT_CAPACITY", "100"));
        c.rateLimitRefillPerSec = Double.parseDouble(env("LB_RATE_LIMIT_REFILL_PER_SEC", "50"));
        c.rateLimitOverrides = parseOverrides(env("LB_RATE_LIMIT_OVERRIDES", ""));
        c.healthIntervalMs = Integer.parseInt(env("LB_HEALTH_INTERVAL_MS", "2000"));
        c.connectTimeoutMs = Integer.parseInt(env("LB_CONNECT_TIMEOUT_MS", "2000"));
        c.idleTimeoutMs = Integer.parseInt(env("LB_IDLE_TIMEOUT_MS", "30000"));
        return c;
    }

    /** Format: "clientA=5:10,clientB=200:400"  (refillPerSec:capacity) */
    public static Map<String, double[]> parseOverrides(String s) {
        Map<String, double[]> m = new HashMap<>();
        if (s == null || s.isBlank()) return m;
        for (String part : s.split(",")) {
            String[] kv = part.trim().split("=");
            String[] rc = kv[1].split(":");
            m.put(kv[0], new double[]{Double.parseDouble(rc[0]), Double.parseDouble(rc[1])});
        }
        return m;
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
