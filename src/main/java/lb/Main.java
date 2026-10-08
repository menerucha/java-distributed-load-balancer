package lb;

import lb.config.Config;
import java.util.logging.Logger;

public final class Main {
    public static void main(String[] args) throws Exception {
        Config cfg = Config.fromEnv();
        LoadBalancer lb = new LoadBalancer(cfg);
        lb.start();
        Logger.getLogger("lb").info("Load balancer up: mode=" + cfg.mode + " algo=" + cfg.algorithm
            + " port=" + lb.port() + " admin=" + lb.adminPort() + " backends=" + cfg.backends
            + " rateLimit=" + (cfg.rateLimitEnabled ? cfg.rateLimitRefillPerSec + "/s burst " + cfg.rateLimitCapacity : "off"));
        Runtime.getRuntime().addShutdownHook(new Thread(lb::stop));
        Thread.currentThread().join();
    }
}
