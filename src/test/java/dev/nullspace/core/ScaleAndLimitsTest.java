package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScaleAndLimitsTest {
    // largest id whose build area still fits inside the world border (ring 3661 is the last full ring)
    static final long LAST_VALID_ID = Allocation.LAST_ID;

    private static ArchiveRegistry registryWith(int n) {
        ArchiveRegistry registry = new ArchiveRegistry();
        for (int i = 0; i < n; i++) { Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic"); a.markInitialized(); }
        return registry;
    }

    private static Optional<Archive> linearAt(ArchiveRegistry r, int x, int z) {
        return r.archives().values().stream().filter(a -> a.allocation().contains(x, z)).findFirst();
    }

    @Test void indexedLookupMatchesLinearScanEverywhere() {
        ArchiveRegistry r = registryWith(200);
        Random rnd = new Random(7);
        List<int[]> points = new ArrayList<>();
        for (Archive a : r.archives().values()) {
            int ox = a.originX(), oz = a.originZ(), R = Allocation.BUILD_RADIUS;
            // exact edges, just outside, the centre, and the cell border between neighbours
            for (int dx : new int[] {-R - 1, -R, 0, R, R + 1, -4096, 4095, 4096}) for (int dz : new int[] {-R - 1, -R, 0, R, R + 1, -4096, 4096}) points.add(new int[] {ox + dx, oz + dz});
        }
        for (int i = 0; i < 20_000; i++) points.add(new int[] {rnd.nextInt(-8 * 8192, 8 * 8192), rnd.nextInt(-8 * 8192, 8 * 8192)});
        points.add(new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}); points.add(new int[] {-1, -1}); points.add(new int[] {0, 0});
        for (int[] p : points) assertEquals(linearAt(r, p[0], p[1]).map(Archive::owner), r.at(p[0], p[1]).map(Archive::owner), "at " + p[0] + "," + p[1]);
    }

    @Test void indexSurvivesRestoreAndRecoveryRemoval() {
        ArchiveRegistry r = registryWith(30);
        ArchiveRegistry copy = ArchiveRegistry.restore(r.nextAllocationId(), r.archives().values());
        for (Archive a : r.archives().values()) assertSame(a, copy.at(a.originX(), a.originZ()).orElseThrow());
        Archive victim = r.archives().values().iterator().next();
        r.removeForRecovery(victim.owner());
        assertTrue(r.at(victim.originX(), victim.originZ()).isEmpty());
        // IDs are not recycled: the next claim gets a fresh cell, and the removed cell stays empty.
        Archive next = r.claim(UUID.randomUUID(), "nullspace:classic");
        assertEquals(30, next.allocationId());
        assertSame(next, r.at(next.originX(), next.originZ()).orElseThrow());
        assertTrue(r.at(victim.originX(), victim.originZ()).isEmpty());
    }

    @Test void lookupAndTicketReconciliationCostWith200Archives() {
        ArchiveRegistry r = registryWith(200);
        List<Archive> list = new ArrayList<>(r.archives().values());
        for (int i = 0; i < 5; i++) { Archive a = list.get(i * 40); a.installAnchor(a.owner()); }
        ServerPolicy policy = new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 720L * 3_600_000L, Set.of(Permission.ENTER));
        Random rnd = new Random(1);
        int[][] pts = new int[1000][];
        for (int i = 0; i < pts.length; i++) { Archive a = list.get(rnd.nextInt(list.size())); pts[i] = new int[] {a.originX() + rnd.nextInt(-1100, 1100), a.originZ() + rnd.nextInt(-1100, 1100)}; }
        long sink = 0;
        for (int warm = 0; warm < 20_000; warm++) sink += r.at(pts[warm % 1000][0], pts[warm % 1000][1]).isPresent() ? 1 : 0;
        long t = System.nanoTime();
        int reps = 200_000;
        for (int i = 0; i < reps; i++) sink += r.at(pts[i % 1000][0], pts[i % 1000][1]).isPresent() ? 1 : 0;
        double atNs = (System.nanoTime() - t) / (double) reps;
        t = System.nanoTime();
        for (int i = 0; i < 2000; i++) sink += linearAt(r, pts[i % 1000][0], pts[i % 1000][1]).isPresent() ? 1 : 0;
        double linearNs = (System.nanoTime() - t) / 2000.0;
        for (int i = 0; i < 2000; i++) sink += TicketPlanner.desired(r.archives().values(), Set.of(), policy).size();
        t = System.nanoTime();
        int plans = 5000;
        for (int i = 0; i < plans; i++) sink += TicketPlanner.desired(r.archives().values(), Set.of(), policy).size();
        double planUs = (System.nanoTime() - t) / plans / 1000.0;
        System.out.printf("SCALE 200 archives: at() %.0f ns indexed vs %.0f ns linear scan; TicketPlanner.desired (5 anchored) %.1f us per reconciliation%n", atNs, linearNs, planUs);
        assertTrue(sink > 0);
        assertTrue(atNs < 5_000, "indexed lookup should be far below a microsecond-scale budget");
    }

    @Test void tickedPlanIsEmptyAndCheapWhenNobodyHasAnAnchor() {
        ArchiveRegistry r = registryWith(200);
        ServerPolicy policy = new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 1L, Set.of(Permission.ENTER));
        assertTrue(TicketPlanner.desired(r.archives().values(), Set.of(), policy).isEmpty());
    }

    @Test void allocationsNearTheWorldBorderAndExhaustion() {
        Allocation last = Allocation.at(LAST_VALID_ID);
        assertTrue(Math.abs((long) last.originX()) + Allocation.BUILD_RADIUS <= Allocation.WORLD_LIMIT);
        assertTrue(Math.abs((long) last.originZ()) + Allocation.BUILD_RADIUS <= Allocation.WORLD_LIMIT);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Allocation.at(LAST_VALID_ID + 1));
        assertTrue(e.getMessage().contains("exhausted"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Allocation.at(-1));
        assertThrows(IllegalArgumentException.class, () -> Allocation.at(Long.MAX_VALUE));
        for (long id : new long[] {0, 1, 8, 9, 10_000, 1_000_000, 53_000_000, LAST_VALID_ID}) {
            Allocation a = Allocation.at(id);
            assertEquals(id, a.id());
            assertEquals(0, a.originX() % Allocation.SPACING);
            assertEquals(0, a.originZ() % Allocation.SPACING);
        }
    }

    @Test void claimWhenExhaustedFailsCleanlyAndChangesNothing() {
        ArchiveRegistry r = ArchiveRegistry.restore(LAST_VALID_ID + 1, List.of());
        long before = r.nextAllocationId();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> r.claim(UUID.randomUUID(), "nullspace:classic"));
        assertTrue(e.getMessage().contains("exhausted"));
        assertEquals(before, r.nextAllocationId());
        assertTrue(r.archives().isEmpty());
        // the last valid id itself is still claimable.
        ArchiveRegistry r2 = ArchiveRegistry.restore(LAST_VALID_ID, List.of());
        assertEquals(LAST_VALID_ID, r2.claim(UUID.randomUUID(), "nullspace:classic").allocationId());
        assertThrows(IllegalArgumentException.class, () -> r2.claim(UUID.randomUUID(), "nullspace:classic"));
    }

    @Test void spiralIsInjectiveAndContiguousUpToOneMillion() {
        Set<Long> seen = new HashSet<>();
        Allocation prev = Allocation.at(0);
        for (long id = 0; id < 1_000_000; id++) {
            Allocation a = Allocation.at(id);
            assertTrue(seen.add(((long) a.originX() << 32) ^ (a.originZ() & 0xffffffffL)), "duplicate cell at id " + id);
            if (id > 0) {
                int steps = Math.abs(a.originX() - prev.originX()) + Math.abs(a.originZ() - prev.originZ());
                assertEquals(Allocation.SPACING, steps, "spiral must move one cell at a time (id " + id + ")");
            }
            prev = a;
        }
    }

    @Test void buildAreasOfNeighbouringAllocationsNeverIntersectUpTo100k() {
        int n = 100_000;
        Map<Long, Integer> cellToId = new HashMap<>();
        for (int id = 0; id < n; id++) {
            Allocation a = Allocation.at(id);
            assertNull(cellToId.put(((long) (a.originX() / Allocation.SPACING) << 32) ^ ((a.originZ() / Allocation.SPACING) & 0xffffffffL), id));
        }
        // the fullest possible room at the farthest grid offset on each side, in every allocation, against all eight neighbours.
        int far = (Allocation.BUILD_RADIUS - 10) / Room.STRIDE;
        for (int id = 0; id < n; id++) {
            Allocation a = Allocation.at(id);
            Bounds mine = extremeBox(a, far);
            assertTrue(allocationBox(a).contains(mine.minX(), mine.minY(), mine.minZ()) && allocationBox(a).contains(mine.maxX(), mine.maxY(), mine.maxZ()),
                "extreme rooms must stay inside the allocation, id " + id);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Integer other = cellToId.get(((long) (a.originX() / Allocation.SPACING + dx) << 32) ^ ((a.originZ() / Allocation.SPACING + dz) & 0xffffffffL));
                if (other == null) continue;
                assertFalse(mine.intersects(extremeBox(Allocation.at(other), far)), "ids " + id + " and " + other + " intersect");
                assertFalse(allocationBox(a).intersects(allocationBox(Allocation.at(other))), "allocation boxes of " + id + " and " + other);
            }
        }
    }

    private static Bounds allocationBox(Allocation a) {
        return new Bounds(a.originX() - Allocation.BUILD_RADIUS, 0, a.originZ() - Allocation.BUILD_RADIUS, a.originX() + Allocation.BUILD_RADIUS, 255, a.originZ() + Allocation.BUILD_RADIUS);
    }

    private static Bounds extremeBox(Allocation a, int far) {
        Room west = new Room(1, -far, -far, 2), east = new Room(2, far, far, 2);
        Bounds w = west.reservedBounds(a), e = east.reservedBounds(a);
        return new Bounds(Math.min(w.minX(), e.minX()), w.minY(), Math.min(w.minZ(), e.minZ()), Math.max(w.maxX(), e.maxX()), w.maxY(), Math.max(w.maxZ(), e.maxZ()));
    }

    @Test void roomGridNeverLeavesTheAllocationAndOverlongChainsAreRefusedCleanly() {
        int far = (Allocation.BUILD_RADIUS - 10) / Room.STRIDE;
        assertDoesNotThrow(() -> new Room(1, far, -far, 2));
        assertThrows(IllegalArgumentException.class, () -> new Room(1, far + 1, 0, 0));
        ArchiveRegistry r = new ArchiveRegistry();
        Archive a = r.claim(UUID.randomUUID(), "nullspace:classic");
        // a single straight chain east: the configured maximum is 49 rooms, but the allocation ends at grid offset 42.
        int sourceId = 0, added = 0;
        IllegalArgumentException refusal = null;
        for (int i = 0; i < 49; i++) {
            try {
                Room room = LayoutPlanner.commitAnnex(a, a.owner(), LayoutPlanner.previewAnnex(a, a.owner(), sourceId, Direction.EAST, 49), 49);
                sourceId = room.id(); added++;
            } catch (IllegalArgumentException e) { refusal = e; break; }
        }
        assertEquals(far, added, "chain stops exactly at the allocation edge");
        assertNotNull(refusal, "going past the edge is an ordinary refusal, not a crash");
        for (Room room : a.rooms().values()) {
            Bounds b = room.reservedBounds(a.allocation());
            assertTrue(allocationBox(a.allocation()).contains(b.minX(), b.minY(), b.minZ()) && allocationBox(a.allocation()).contains(b.maxX(), b.maxY(), b.maxZ()));
        }
    }
}
