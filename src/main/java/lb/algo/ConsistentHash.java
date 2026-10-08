package lb.algo;

import lb.core.Backend;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Consistent hashing with virtual nodes (VNODES * weight per backend) on an MD5-derived 64-bit ring.
 * The same client key always maps to the same backend; when a backend leaves, only its keys move.
 */
public final class ConsistentHash implements RoutingStrategy {
    static final int VNODES = 150;
    private record Ring(long signature, TreeMap<Long, Backend> map) {}
    private volatile Ring ring = new Ring(-1, new TreeMap<>());
    private static final ThreadLocal<MessageDigest> MD5 = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("MD5"); } catch (Exception e) { throw new IllegalStateException(e); } });

    @Override public String name() { return "consistent_hash"; }

    @Override public Backend select(List<Backend> healthy, String clientKey) {
        TreeMap<Long, Backend> m = ringFor(healthy);
        Map.Entry<Long, Backend> e = m.ceilingEntry(hash(clientKey == null ? "" : clientKey));
        return (e != null ? e : m.firstEntry()).getValue();
    }

    private TreeMap<Long, Backend> ringFor(List<Backend> healthy) {
        long sig = healthy.size();
        for (Backend b : healthy) sig += (long) b.id().hashCode() * 31 + b.weight;
        Ring r = ring;
        if (r.signature == sig) return r.map;
        synchronized (this) {
            if (ring.signature == sig) return ring.map;
            TreeMap<Long, Backend> m = new TreeMap<>();
            for (Backend b : healthy)
                for (int i = 0; i < VNODES * b.weight; i++) m.put(hash(b.id() + "#" + i), b);
            ring = new Ring(sig, m);
            return m;
        }
    }

    static long hash(String s) {
        byte[] d = MD5.get().digest(s.getBytes(StandardCharsets.UTF_8));
        long h = 0;
        for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xff);
        return h;
    }
}
