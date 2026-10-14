package dev.nullspace.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

// caps client requests per player so a client cannot make the server scan directories or load chunks at network
// speed. server thread only
public final class ActionLimiter {
    private static final int PRUNE_ABOVE = 512;
    private final int maxPerWindow;
    private final long windowTicks;
    private final Map<UUID, long[]> recent = new HashMap<>();

    public ActionLimiter(int maxPerWindow, long windowTicks) {
        if (maxPerWindow < 1 || windowTicks < 1) throw ArchiveException.corrupt("rate-limit");
        this.maxPerWindow = maxPerWindow; this.windowTicks = windowTicks;
    }

    public boolean tryAcquire(UUID player, long tick) {
        if (recent.size() > PRUNE_ABOVE) prune(tick);
        long[] stamps = recent.computeIfAbsent(player, id -> new long[maxPerWindow]);
        int oldest = 0;
        for (int i = 1; i < stamps.length; i++) if (stamps[i] < stamps[oldest]) oldest = i;
        long now = tick + 1, then = stamps[oldest];
        if (then != 0 && now >= then && now - then < windowTicks) return false;
        stamps[oldest] = now;
        return true;
    }

    private void prune(long tick) {
        long now = tick + 1;
        for (Iterator<long[]> it = recent.values().iterator(); it.hasNext(); ) {
            long newest = 0;
            for (long stamp : it.next()) newest = Math.max(newest, stamp);
            if (now < newest || now - newest >= windowTicks) it.remove();
        }
    }
}
