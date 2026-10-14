package dev.nullspace.core;

import java.util.Optional;
import java.util.UUID;

// recall module rules. while installed, the owner's death outside the archive is remembered, and on respawning inside
// their own archive the exit leads to that spot once, within a time window. owners only
public final class Recall {
    private Recall() {}

    public static final int DEFAULT_MINUTES = 60, MIN_MINUTES = 1, MAX_MINUTES = 1440;

    // an exit gateway keeps the target it was opened with, and one walked through stays in the archive while its
    // owner is away, so its target can be out of date. compared by dimension and distance, with a small tolerance
    public static boolean sameSpot(String dimA, double ax, double ay, double az, String dimB, double bx, double by, double bz) {
        if (!dimA.equals(dimB)) return false;
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return dx * dx + dy * dy + dz * dz <= 1.5 * 1.5;
    }

    public static int clampMinutes(int minutes) { return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes)); }

    public static boolean expired(RecallPoint point, long now, int minutes) {
        long window = clampMinutes(minutes) * 60_000L;
        long age = now - point.diedAtMillis();
        return age < 0 || age >= window; // a clock that went backwards invalidates the point rather than extending it
    }

    public static boolean recordDeath(Archive archive, UUID dead, boolean diedInsideArchive, ReturnLocation where, long now, boolean enabled) {
        if (!archive.owner().equals(dead) || !archive.recallInstalled() || !enabled || diedInsideArchive) return false;
        archive.setRecall(new RecallPoint(where, now, false));
        return true;
    }

    public static boolean armOnRespawn(Archive archive, UUID player, boolean inOwnArchive, long now, int minutes, boolean enabled) {
        if (!archive.owner().equals(player)) return false;
        RecallPoint point = archive.recallPoint();
        if (point == null) return false;
        if (!inOwnArchive || !enabled || !archive.recallInstalled() || expired(point, now, minutes)) { archive.setRecall(null); return false; }
        archive.setRecall(point.arm());
        return true;
    }

    public static Optional<ReturnLocation> target(Archive archive, UUID player, long now, int minutes, boolean enabled) {
        RecallPoint point = archive.recallPoint();
        if (point == null || !point.armed() || !enabled || !archive.recallInstalled() || !archive.owner().equals(player) || expired(point, now, minutes))
            return Optional.empty();
        return Optional.of(point.where());
    }

    public static boolean clear(Archive archive, UUID player) {
        if (!archive.owner().equals(player) || archive.recallPoint() == null) return false;
        archive.setRecall(null);
        return true;
    }

    public static boolean dropExpired(Archive archive, long now, int minutes) {
        RecallPoint point = archive.recallPoint();
        if (point == null || !expired(point, now, minutes)) return false;
        archive.setRecall(null);
        return true;
    }
}
