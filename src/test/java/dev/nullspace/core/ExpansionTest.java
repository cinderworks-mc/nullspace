package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ExpansionTest {
    private final ArchiveRegistry registry = new ArchiveRegistry();
    private final Archive archive = registry.claim(UUID.randomUUID(), "nullspace:classic");
    private final UUID owner = archive.owner();

    private void widen(int id) { LayoutPlanner.commitExpansion(archive, owner, LayoutPlanner.previewExpansion(archive, owner, id)); }
    private void raise(int id) { LayoutPlanner.commitExpansion(archive, owner, LayoutPlanner.previewExpansion(archive, owner, id, LayoutPlanner.Axis.HEIGHT)); }

    @Test void sizesAndBoundsForEveryCombination() {
        assertArrayEquals(new int[] {9, 13, 17, 21}, Room.widths());
        assertArrayEquals(new int[] {6, 9, 12}, Room.heights());
        for (int wt = 0; wt < 4; wt++) for (int ht = 0; ht < 3; ht++) {
            Room room = new Room(0, 0, 0, wt, ht);
            Bounds in = room.interior(archive.allocation()), shell = room.shell(archive.allocation());
            assertEquals(Room.widthOfTier(wt), room.width());
            assertEquals(Room.heightOfTier(ht), room.height());
            assertEquals(Room.FLOOR_Y + 1, in.minY());
            assertEquals(Room.FLOOR_Y + room.height(), in.maxY(), "interior is exactly the clear height");
            assertEquals(Room.FLOOR_Y, shell.minY());
            assertEquals(Room.FLOOR_Y + room.height() + 1, shell.maxY());
            assertEquals(room.width(), in.maxX() - in.minX() + 1);
            assertEquals(room.width() + 2, shell.maxZ() - shell.minZ() + 1);
        }
        // a room with the old constructor is the original six-high room, and the old three-argument bounds still use its own height.
        Room old = new Room(0, 0, 0, 1);
        assertEquals(6, old.height());
        assertEquals(old.bounds(archive.allocation(), 17, true), old.bounds(archive.allocation(), 17, 6, true));
        assertEquals(-1, new Room(0, 0, 0, 3, 2).nextWidth());
        assertEquals(-1, new Room(0, 0, 0, 3, 2).nextHeight());
        assertEquals(21, new Room(0, 0, 0, 2).nextWidth());
        assertEquals(17, new Room(1, 1, 0, 1).nextWidth());
        assertEquals(-1, new Room(1, 1, 0, 2).nextWidth(), "only the starter room goes past 17");
        assertEquals(9, new Room(1, 1, 0, 0).nextHeight());
        assertEquals(21, Room.nextWidth(0, 17));
        assertEquals(-1, Room.nextWidth(5, 17));
        assertEquals(12, Room.nextHeight(9));
        assertEquals(-1, Room.nextHeight(12));
    }

    @Test void onlyTheStarterRoomCanBeAHall() {
        assertDoesNotThrow(() -> new Room(0, 0, 0, 3));
        assertThrows(ArchiveException.class, () -> new Room(1, 1, 0, 3), "a saved non-starter room at width 21 is corrupt");
        assertThrows(ArchiveException.class, () -> new Room(0, 0, 0, 4));
        assertThrows(ArchiveException.class, () -> new Room(0, 0, 0, 0, 3));
        assertThrows(ArchiveException.class, () -> new Room(0, 0, 0, 0, -1));
    }

    @Test void finishCoordinatesCoverTheTallestWallAndTheHall() {
        Room room = new Room(0, 0, 0, 0);
        assertDoesNotThrow(() -> room.setIndividualFinish(new Room.FinishCoordinate(Surface.NORTH, 0, 13), "nullspace:copper"));
        assertDoesNotThrow(() -> room.setIndividualFinish(new Room.FinishCoordinate(Surface.WEST, -11, 12), "nullspace:copper"));
        assertDoesNotThrow(() -> room.setIndividualFinish(new Room.FinishCoordinate(Surface.CEILING, 10, -10), "nullspace:copper"));
        assertThrows(ArchiveException.class, () -> room.setIndividualFinish(new Room.FinishCoordinate(Surface.NORTH, 0, 14), "nullspace:copper"));
        assertThrows(ArchiveException.class, () -> room.setIndividualFinish(new Room.FinishCoordinate(Surface.NORTH, 12, 3), "nullspace:copper"));
        // ... and such an override survives a restore (what loading a save does), which is what keeps an archive readable.
        Room restored = Room.restore(0, 0, 0, 0, 2, Map.of(), room.finishes(), room.individualFinishes());
        assertEquals("nullspace:copper", restored.finish(new Room.FinishCoordinate(Surface.NORTH, 0, 13)));
        assertEquals(2, restored.heightTier());
    }

    @Test void neighboursOfAHallStayClearAndTheTunnelKeepsItsLength() {
        // room 0 reserves 21 wide, every other room 17: no two grid cells ever intersect, at any size.
        int[][] cells = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {2, 0}};
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) rooms.add(new Room(i, cells[i][0], cells[i][1], i == 0 ? 3 : 2, 2));
        for (Room a : rooms) for (Room b : rooms) if (a != b) assertFalse(a.reservedBounds(archive.allocation()).intersects(b.reservedBounds(archive.allocation())),
            a.id() + " vs " + b.id());
        Room hall = rooms.get(0), east = rooms.get(1);
        // hall wall at +11, neighbour wall at 24 - 9 = 15: the three blocks between them are the tunnel
        assertEquals(hall.shell(archive.allocation()).maxX() + 4, east.shell(archive.allocation()).minX());
        assertEquals(3, east.shell(archive.allocation()).minX() - hall.shell(archive.allocation()).maxX() - 1);
        // the whole archive still restores: the overlap check at load time is the migration gate
        Archive a = full(true);
        assertDoesNotThrow(() -> Archive.restore(a.owner(), a.allocation(), a.starterPreset(), true, false, a.settings(), a.rooms(), a.invitations(), a.activeVisits(), a.presets()));
    }

    private Archive full(boolean max) {
        Archive a = new ArchiveRegistry().claim(UUID.randomUUID(), "nullspace:classic");
        UUID o = a.owner();
        Direction[] order = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
        int added = 0;
        for (int source = 0; added < 8 && source < a.rooms().size(); source++)
            for (Direction d : order) {
                if (added >= 8) break;
                try { LayoutPlanner.commitAnnex(a, o, LayoutPlanner.previewAnnex(a, o, source, d, 9), 9); added++; }
                catch (IllegalArgumentException blocked) { /* occupied */ }
            }
        if (max) for (Room r : List.copyOf(a.rooms().values())) {
            while (r.nextWidth() > 0) LayoutPlanner.commitExpansion(a, o, LayoutPlanner.previewExpansion(a, o, r.id()));
            while (r.nextHeight() > 0) LayoutPlanner.commitExpansion(a, o, LayoutPlanner.previewExpansion(a, o, r.id(), LayoutPlanner.Axis.HEIGHT));
        }
        return a;
    }

    @Test void blockCountsMatchTheResearchTable() {
        // 17 -> 21 at height 6 and at height 12 (the worst hall), no neighbours so no tunnel exclusions
        widen(0); widen(0);
        var hall6 = LayoutPlanner.previewExpansion(archive, owner, 0);
        assertEquals(864, hall6.addShell().size());
        assertEquals(432, hall6.removeShell().size());
        raise(0); raise(0);
        var hall12 = LayoutPlanner.previewExpansion(archive, owner, 0);
        assertEquals(1392, hall12.addShell().size());
        assertEquals(864, hall12.removeShell().size());
        assertEquals(21, hall12.newWidth());
        assertEquals(12, hall12.newInterior().maxY() - Room.FLOOR_Y);
    }

    @Test void ceilingStepsAddTheNewRoofAndRemoveTheOldOne() {
        // placed: the new roof plane ((w+2)^2) plus two new rows of wall ring (4w+4 each; the old roof ring becomes the third); removed: the old roof over the interior (w^2)
        int[] width = {9, 13, 17, 21}, placed = {201, 337, 505, 705}, removed = {81, 169, 289, 441};
        for (int tier = 0; tier < 4; tier++) {
            Archive a = new ArchiveRegistry().claim(UUID.randomUUID(), "nullspace:classic");
            for (int i = 0; i < tier; i++) LayoutPlanner.commitExpansion(a, a.owner(), LayoutPlanner.previewExpansion(a, a.owner(), 0));
            var plan = LayoutPlanner.previewExpansion(a, a.owner(), 0, LayoutPlanner.Axis.HEIGHT);
            assertEquals(width[tier], a.room(0).width());
            assertEquals(placed[tier], plan.addShell().size(), "placed at width " + width[tier]);
            assertEquals(removed[tier], plan.removeShell().size(), "removed at width " + width[tier]);
            assertEquals(9, plan.newHeight());
            // the old top wall ring becomes plain wall, so it is repainted
            assertEquals(4 * width[tier] + 4, plan.restyleShell().size());
            // the whole old ceiling plane is removed and nothing else but ceiling
            for (BlockPoint p : plan.removeShell()) assertEquals(Room.FLOOR_Y + 7, p.y());
            // interior volume only ever grows
            assertTrue(plan.newInterior().minX() == plan.oldInterior().minX() && plan.newInterior().maxY() == plan.oldInterior().maxY() + 3);
        }
    }

    @Test void raisingNeverMovesWalls() {
        var plan = LayoutPlanner.previewExpansion(archive, owner, 0, LayoutPlanner.Axis.HEIGHT);
        Bounds oldShell = archive.room(0).shell(archive.allocation());
        for (BlockPoint p : plan.addShell()) assertTrue(p.y() > oldShell.maxY(), "only blocks above the old roof ring are added: " + p);
        LayoutPlanner.commitExpansion(archive, owner, plan);
        assertEquals(9, archive.room(0).height());
        assertEquals(9, archive.room(0).width());
    }

    @Test void raisingAnyRoomWorksAndStopsAtTwelve() {
        LayoutPlanner.commitAnnex(archive, owner, LayoutPlanner.previewAnnex(archive, owner, 0, Direction.EAST, 9), 9);
        raise(1); raise(1);
        assertEquals(12, archive.room(1).height());
        assertEquals(6, archive.room(0).height());
        var refusal = assertThrows(ArchiveException.class, () -> LayoutPlanner.previewExpansion(archive, owner, 1, LayoutPlanner.Axis.HEIGHT));
        assertEquals("error.nullspace.room.max_height", refusal.key());
        assertThrows(SecurityException.class, () -> LayoutPlanner.previewExpansion(archive, UUID.randomUUID(), 1, LayoutPlanner.Axis.HEIGHT));
    }

    @Test void thePlanOfOneAxisIsStaleAfterTheOther() {
        var widen = LayoutPlanner.previewExpansion(archive, owner, 0);
        var raise = LayoutPlanner.previewExpansion(archive, owner, 0, LayoutPlanner.Axis.HEIGHT);
        LayoutPlanner.commitExpansion(archive, owner, raise);
        assertEquals("error.nullspace.room.stale_preview", assertThrows(ArchiveException.class, () -> LayoutPlanner.commitExpansion(archive, owner, widen)).key());
        assertEquals("error.nullspace.room.stale_preview", assertThrows(ArchiveException.class, () -> LayoutPlanner.commitExpansion(archive, owner, raise)).key());
    }

    @Test void serverCapsVetoOnlyFutureUpgradesAndNeverShrink() {
        ServerPolicy narrow = new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 1000, Set.of(Permission.ENTER), true, 60, 17, 6);
        // ceiling switched off, hall switched off
        assertEquals("error.nullspace.room.height_vetoed", assertThrows(ArchiveException.class, () -> LayoutPlanner.previewExpansion(archive, owner, 0, LayoutPlanner.Axis.HEIGHT, narrow)).key());
        widen(0); widen(0);
        assertEquals("error.nullspace.room.width_vetoed", assertThrows(ArchiveException.class, () -> LayoutPlanner.previewExpansion(archive, owner, 0, LayoutPlanner.Axis.WIDTH, narrow)).key());
        assertEquals(17, archive.room(0).width());
        // a cap below what a room already is does not change it; only the next step is refused
        raise(0); raise(0);
        ServerPolicy low = new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 1000, Set.of(Permission.ENTER), true, 60, 9, 9);
        assertEquals(12, archive.room(0).height());
        assertEquals(17, archive.room(0).width());
        assertThrows(ArchiveException.class, () -> LayoutPlanner.previewExpansion(archive, owner, 0, LayoutPlanner.Axis.WIDTH, low));
        assertEquals(12, archive.room(0).height());
        // caps in between snap down; nonsense does not throw
        ServerPolicy odd = new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 1000, Set.of(Permission.ENTER), true, 60, 20, 11);
        assertEquals(17, odd.maxRoomWidth());
        assertEquals(9, odd.maxCeilingHeight());
        ServerPolicy tiny = new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 1000, Set.of(Permission.ENTER), true, 60, -5, 0);
        assertEquals(9, tiny.maxRoomWidth());
        assertEquals(6, tiny.maxCeilingHeight());
        // the old constructors keep every step open
        assertEquals(21, ServerPolicy.DEFAULT.maxRoomWidth());
        assertEquals(12, ServerPolicy.DEFAULT.maxCeilingHeight());
        assertEquals(21, new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 1000, Set.of(Permission.ENTER), true, 60).maxRoomWidth());
        assertEquals(12, new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 1000, Set.of(Permission.ENTER)).maxCeilingHeight());
    }

    @Test void aHallNeverFillsTheTunnelToAnArchiveNeighbour() {
        LayoutPlanner.commitAnnex(archive, owner, LayoutPlanner.previewAnnex(archive, owner, 0, Direction.EAST, 9), 9);
        LayoutPlanner.commitAnnex(archive, owner, LayoutPlanner.previewAnnex(archive, owner, 0, Direction.NORTH, 9), 9);
        widen(0); widen(0);
        raise(0);
        var plan = LayoutPlanner.previewExpansion(archive, owner, 0);
        assertEquals(21, plan.newWidth());
        int cx = archive.room(0).centerX(archive.allocation()), cz = archive.room(0).centerZ(archive.allocation());
        for (BlockPoint p : plan.addShell()) {
            assertFalse(p.y() >= Room.FLOOR_Y + 1 && p.y() <= Room.FLOOR_Y + 3 && Math.abs(p.z() - cz) <= 1 && p.x() > cx, "shell in the east tunnel at " + p);
            assertFalse(p.y() >= Room.FLOOR_Y + 1 && p.y() <= Room.FLOOR_Y + 3 && Math.abs(p.x() - cx) <= 1 && p.z() < cz, "shell in the north tunnel at " + p);
        }
        for (BlockPoint p : plan.removeShell()) assertFalse(LayoutPlanner.inConnectedPassage(archive, archive.room(0), p));
    }

    @Test void progressionPricesEveryStep() {
        assertEquals(new Progression.Cost(1, 4, 0), Progression.expansion(0, 1, 2));
        assertEquals(new Progression.Cost(1, 4, 0, List.of()), Progression.expansion(0, 1, 2), "old three argument constructor equals the empty-extras cost");
        assertEquals(new Progression.Cost(1, 2, 0, List.of(new Progression.Extra("tempad:chronon_cell", 1))), Progression.ceiling(0, 1, 2, true));
        assertEquals(new Progression.Cost(2, 4, 0, List.of(new Progression.Extra("tempad:chronon_cell", 1))), Progression.ceiling(1, 1, 2, true));
        assertEquals(new Progression.Cost(1, 2, 0), Progression.ceiling(0, 1, 2, false));
        assertEquals(new Progression.Cost(4, 0, 2, List.of(new Progression.Extra("tempad:time_steel", 2))), Progression.hall(4, 2, true));
        assertEquals(new Progression.Cost(4, 0, 2), Progression.hall(4, 2, false));
        assertEquals("error.nullspace.room.max_height", assertThrows(ArchiveException.class, () -> Progression.ceiling(2, 1, 2, true)).key());
        assertThrows(IllegalArgumentException.class, () -> new Progression.Cost(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Progression.Extra("no id", 1));
        assertThrows(IllegalArgumentException.class, () -> new Progression.Extra("tempad:chronon_cell", 0));
        assertThrows(UnsupportedOperationException.class, () -> Progression.hall(4, 2, true).extras().add(new Progression.Extra("a:b", 1)));
    }

    @Test void pendingEditsKeepTheirOldShapeAndKnowTheNewOne() {
        // an entry as the old build wrote it: no height fields at all
        PendingEdit old = new PendingEdit(PendingEdit.Kind.EXPAND, 1, 1, 0, 0, 0, 0);
        assertEquals(0, old.oldHeightTier());
        PendingEdit raise = PendingEdit.raise(0, 0, 1);
        assertEquals(PendingEdit.Kind.RAISE, raise.kind());
        PendingEdit fromZero = PendingEdit.raise(0, 0, 0);
        assertFalse(fromZero.alreadyApplied(archive), "the room still has height tier 0: the domain shows the old state");
        archive.room(0).raise();
        assertTrue(fromZero.alreadyApplied(archive), "the room left tier 0, so the edit already reached the domain");
        assertTrue(raise.alreadyApplied(archive) == false, "an entry that started from tier 1 does not match a room that is at 1");
        assertDoesNotThrow(() -> PendingEdit.expand(0, 3));
        assertThrows(ArchiveException.class, () -> PendingEdit.expand(0, 4));
        assertThrows(ArchiveException.class, () -> PendingEdit.raise(0, 0, 3));
        // a removal remembers the leaf's height so its shell can still be cleared at its real size
        assertEquals(2, PendingEdit.remove(0, 1, 1, 2, Direction.EAST, 1, 0).oldHeightTier());
        assertEquals(0, PendingEdit.remove(0, 1, 1, Direction.EAST, 1, 0).oldHeightTier());
    }

    @Test void anchorChunksStayBoundedWithAHallAtFullHeight() {
        for (boolean max : new boolean[] {false, true}) {
            Archive a = full(max);
            assertEquals(9, a.rooms().size());
            int chunks = TicketPlanner.roomChunks(a).size();
            assertTrue(chunks <= 42, "nine rooms never need more than the measured worst case, got " + chunks);
            assertTrue(chunks <= ServerPolicy.DEFAULT.maxChunksPerArchive());
        }
        // the hall itself adds no chunk: the starter room's 23-wide shell lies in the same chunk columns as its 19-wide one
        Archive noHall = full(true);
        int withHall = TicketPlanner.roomChunks(noHall).size();
        // the same archive again, with the starter room held at 17:
        Archive held = new ArchiveRegistry().claim(UUID.randomUUID(), "nullspace:classic");
        UUID ho = held.owner();
        Direction[] order = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
        for (int source = 0, added = 0; added < 8 && source < held.rooms().size(); source++) for (Direction d : order) {
            if (added >= 8) break;
            try { LayoutPlanner.commitAnnex(held, ho, LayoutPlanner.previewAnnex(held, ho, source, d, 9), 9); added++; } catch (IllegalArgumentException blocked) { /* occupied */ }
        }
        for (Room r : List.copyOf(held.rooms().values())) {
            while (r.nextWidth() > 0 && !(r.id() == 0 && r.width() == 17)) LayoutPlanner.commitExpansion(held, ho, LayoutPlanner.previewExpansion(held, ho, r.id()));
            while (r.nextHeight() > 0) LayoutPlanner.commitExpansion(held, ho, LayoutPlanner.previewExpansion(held, ho, r.id(), LayoutPlanner.Axis.HEIGHT));
        }
        assertEquals(17, held.room(0).width());
        assertEquals(TicketPlanner.roomChunks(held).size(), withHall, "making the starter room a Grand Hall adds no chunk");
        // random shapes, all rooms at maximum size, stay within the same worst case
        Random rnd = new Random(11);
        int worst = 0;
        for (int trial = 0; trial < 300; trial++) {
            Archive a = new ArchiveRegistry().claim(UUID.randomUUID(), "nullspace:classic");
            UUID o = a.owner();
            while (a.rooms().size() < 9) {
                int source = rnd.nextInt(a.rooms().size());
                Direction d = Direction.values()[rnd.nextInt(4)];
                try { LayoutPlanner.commitAnnex(a, o, LayoutPlanner.previewAnnex(a, o, new ArrayList<>(a.rooms().keySet()).get(source), d, 9), 9); }
                catch (IllegalArgumentException blocked) { /* occupied or doorway used */ }
            }
            for (Room r : List.copyOf(a.rooms().values())) {
                while (r.nextWidth() > 0) LayoutPlanner.commitExpansion(a, o, LayoutPlanner.previewExpansion(a, o, r.id()));
                while (r.nextHeight() > 0) LayoutPlanner.commitExpansion(a, o, LayoutPlanner.previewExpansion(a, o, r.id(), LayoutPlanner.Axis.HEIGHT));
            }
            worst = Math.max(worst, TicketPlanner.roomChunks(a).size());
        }
        assertTrue(worst <= 42, "worst random 9 room shape at full size: " + worst);
        // and an anchored archive of that size really gets its tickets under the default budget of 81
        Archive a = full(true); a.markInitialized(); a.installAnchor(a.owner());
        Set<UUID> owners = TicketPlanner.desired(List.of(a), Set.of(), ServerPolicy.DEFAULT).stream().map(TicketPlanner.Ticket::owner).collect(Collectors.toSet());
        assertEquals(Set.of(a.owner()), owners);
    }
}
