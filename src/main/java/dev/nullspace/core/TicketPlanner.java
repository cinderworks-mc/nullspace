package dev.nullspace.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TicketPlanner {
    private TicketPlanner() {}
    public record Chunk(int x, int z) implements Comparable<Chunk> {
        @Override public int compareTo(Chunk other) { int c = Integer.compare(x, other.x); return c != 0 ? c : Integer.compare(z, other.z); }
    }
    public record Ticket(UUID owner, Chunk chunk) {}
    public record Transition(Set<Ticket> acquire, Set<Ticket> release, Set<Ticket> desired) {
        public Transition { acquire = Set.copyOf(acquire); release = Set.copyOf(release); desired = Set.copyOf(desired); }
    }
    public static Set<Chunk> roomChunks(Archive archive) {
        Set<Chunk> result = new HashSet<>();
        for (Room room : archive.rooms().values()) {
            Bounds bounds = room.shell(archive.allocation());
            for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX(), 16); x++) {
                for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ(), 16); z++) result.add(new Chunk(x, z));
            }
        }
        for (Bounds bounds : LayoutPlanner.tunnelInteriors(archive)) {
            for (int x = Math.floorDiv(bounds.minX() - 1, 16); x <= Math.floorDiv(bounds.maxX() + 1, 16); x++) {
                for (int z = Math.floorDiv(bounds.minZ() - 1, 16); z <= Math.floorDiv(bounds.maxZ() + 1, 16); z++) result.add(new Chunk(x, z));
            }
        }
        return Set.copyOf(result);
    }
    public static Set<Ticket> desired(Collection<Archive> archives, Set<UUID> occupiedOwners, ServerPolicy policy) {
        if (!policy.allowAnchors() || policy.maxAnchoredArchives() == 0) return Set.of();
        Set<Ticket> result = new LinkedHashSet<>();
        int anchored = 0;
        // filter before sorting: with hundreds of archives only a handful have an anchor, so the common case is one cheap scan and no sort.
        List<Archive> candidates = new ArrayList<>();
        for (Archive archive : archives) if (archive.anchorInstalled() && archive.initialized() && !occupiedOwners.contains(archive.owner())) candidates.add(archive);
        if (candidates.isEmpty()) return Set.of();
        candidates.sort(Comparator.comparingLong(Archive::allocationId));
        for (Archive archive : candidates) {
            Set<Chunk> chunks = roomChunks(archive);
            // an over-budget archive gets no anchor tickets, so a workshop is not half active
            if (chunks.size() > policy.maxChunksPerArchive()) continue;
            if (anchored >= policy.maxAnchoredArchives()) break;
            chunks.stream().sorted().forEach(chunk -> result.add(new Ticket(archive.owner(), chunk)));
            anchored++;
        }
        return Set.copyOf(result);
    }
    public enum Grant { GRANTED, TOO_LARGE, NO_SLOT }
    // whether an anchor on this archive would get tickets without taking them from another archive, ignoring who is
    // inside right now
    public static Grant grantFor(Collection<Archive> archives, Archive archive, ServerPolicy policy) {
        if (!policy.allowAnchors() || policy.maxAnchoredArchives() == 0) return Grant.NO_SLOT;
        if (roomChunks(archive).size() > policy.maxChunksPerArchive()) return Grant.TOO_LARGE;
        int active = 0;
        for (Archive other : archives) {
            if (other != archive && other.anchorInstalled() && other.initialized() && roomChunks(other).size() <= policy.maxChunksPerArchive()) active++;
        }
        return active < policy.maxAnchoredArchives() ? Grant.GRANTED : Grant.NO_SLOT;
    }
    public static Transition transition(Set<Ticket> currentlyOwned, Set<Ticket> desired) {
        Set<Ticket> acquire = new HashSet<>(desired); acquire.removeAll(currentlyOwned);
        Set<Ticket> release = new HashSet<>(currentlyOwned); release.removeAll(desired);
        return new Transition(acquire, release, desired);
    }
}
