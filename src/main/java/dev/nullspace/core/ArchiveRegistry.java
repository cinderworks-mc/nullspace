package dev.nullspace.core;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// server thread only. claiming again just replaces the entry
public final class ArchiveRegistry {
    private final Map<UUID, Archive> archives = new HashMap<>();
    private final Map<Long, Archive> byCell = new HashMap<>();
    private long nextAllocationId;

    private static long cellKey(int cellX, int cellZ) { return ((long) cellX << 32) ^ (cellZ & 0xffffffffL); }
    private static long cellKeyOf(Allocation a) { return cellKey(a.originX() / Allocation.SPACING, a.originZ() / Allocation.SPACING); }

    public Archive claim(UUID owner, String preset) {
        Archive existing = archives.get(owner);
        if (existing != null) return existing;
        Archive archive = new Archive(owner, Allocation.at(nextAllocationId), preset);
        archives.put(owner, archive); byCell.put(cellKeyOf(archive.allocation()), archive); nextAllocationId++;
        return archive;
    }
    public static ArchiveRegistry restore(long nextAllocationId, Collection<Archive> saved) {
        ArchiveRegistry registry = new ArchiveRegistry();
        if (nextAllocationId < 0) throw ArchiveException.corrupt("allocation-counter");
        HashSet<Long> allocationIds = new HashSet<>();
        for (Archive archive : saved) {
            if (registry.archives.putIfAbsent(archive.owner(), archive) != null || !allocationIds.add(archive.allocationId())) {
                throw ArchiveException.corrupt("duplicate-allocation");
            }
            if (!archive.allocation().equals(Allocation.at(archive.allocationId()))) throw ArchiveException.corrupt("allocation-coordinates");
            registry.byCell.put(cellKeyOf(archive.allocation()), archive);
            nextAllocationId = Math.max(nextAllocationId, archive.allocationId() + 1);
        }
        registry.nextAllocationId = nextAllocationId;
        return registry;
    }
    public long nextAllocationId() { return nextAllocationId; }
    public Map<UUID, Archive> archives() { return Collections.unmodifiableMap(archives); }
    public Optional<Archive> find(UUID owner) { return Optional.ofNullable(archives.get(owner)); }
    // cheap, because it runs for every protection event and every second per occupant
    public Optional<Archive> at(int x, int z) {
        int half = Allocation.SPACING / 2;
        Archive candidate = byCell.get(cellKey(Math.floorDiv(x + half, Allocation.SPACING), Math.floorDiv(z + half, Allocation.SPACING)));
        return candidate != null && candidate.allocation().contains(x, z) ? Optional.of(candidate) : Optional.empty();
    }
    // allocation ids are not recycled on recovery
    public Optional<Archive> removeForRecovery(UUID owner) {
        Archive removed = archives.remove(owner);
        if (removed != null) byCell.remove(cellKeyOf(removed.allocation()));
        return Optional.ofNullable(removed);
    }
}
