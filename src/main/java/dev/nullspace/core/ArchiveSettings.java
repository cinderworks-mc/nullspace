package dev.nullspace.core;

// what the owner asked for; the real permission is capped by ServerPolicy
public record ArchiveSettings(boolean keepInventory, boolean keepXp, boolean allowSpawn) {
    public static final ArchiveSettings DEFAULT = new ArchiveSettings(false, false, true);
}
