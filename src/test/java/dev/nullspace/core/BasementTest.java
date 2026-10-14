package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasementTest {
    private final ArchiveRegistry registry = new ArchiveRegistry();
    private final Archive archive = claim();
    private final UUID owner = archive.owner();
    private final Allocation alloc = archive.allocation();

    private Archive claim() {
        // allocation 0 is at the origin; allocate a few so later tests also see off-origin archives
        Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic");
        a.markInitialized();
        return a;
    }

    private Archive installed() {
        Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic");
        a.markInitialized();
        a.installBasement(a.owner());
        return a;
    }


    @Test void boundsAreTheStarterRoomShiftedEightDown() {
        Bounds shell = Basement.shell(alloc), interior = Basement.interior(alloc);
        assertEquals(56, shell.minY()); assertEquals(63, shell.maxY());
        assertEquals(57, interior.minY()); assertEquals(62, interior.maxY());
        assertEquals(15, shell.maxX() - shell.minX() + 1);
        assertEquals(13, interior.maxX() - interior.minX() + 1);
        assertEquals(13, interior.maxZ() - interior.minZ() + 1);
        assertEquals(alloc.originX() - 7, shell.minX());
        assertEquals(alloc.originZ() + 7, shell.maxZ());
        assertEquals(Room.FLOOR_Y - 8, Basement.FLOOR_Y);
        assertEquals(6, Basement.CLEAR_HEIGHT);
        // the starter room's floor shell (y 64) is an independent layer right above the hall's ceiling shell (y 63).
        assertEquals(Room.FLOOR_Y - 1, Basement.CEILING_Y);
        // 15 x 15 x 8 minus the interior: the research figure
        assertEquals(786, Basement.shellBlockCount());
        int counted = 0;
        for (int x = shell.minX(); x <= shell.maxX(); x++) for (int y = shell.minY(); y <= shell.maxY(); y++) for (int z = shell.minZ(); z <= shell.maxZ(); z++)
            if (!interior.contains(x, y, z)) counted++;
        assertEquals(786, counted);
    }

    @Test void theHallFitsOneChunkSectionAndTheWorld() {
        Bounds shell = Basement.shell(alloc);
        assertTrue(shell.minY() >= 0 && shell.maxY() <= 127, "inside the 128-high archive dimension");
        assertEquals(Math.floorDiv(shell.minY(), 16), Math.floorDiv(shell.maxY(), 16), "one 16-high chunk section (y 48..63)");
        assertEquals(3, Math.floorDiv(shell.minY(), 16));
    }

    @Test void theHallNeverIntersectsAnyRoomOrTunnelAtAnyGrowth() {
        // every room box the archive can ever reserve (starter hall 21x21x12, other rooms 17x17x12 on the 24-stride grid) starts at y 64.
        Bounds hall = Basement.shell(alloc);
        for (int wt = 0; wt < 4; wt++) for (int ht = 0; ht < 3; ht++) {
            Room starter = new Room(0, 0, 0, wt, ht);
            assertFalse(hall.intersects(starter.shell(alloc)), "starter " + wt + "/" + ht);
            assertFalse(hall.intersects(starter.reservedBounds(alloc)));
        }
        for (int gx = -3; gx <= 3; gx++) for (int gz = -3; gz <= 3; gz++) {
            if (gx == 0 && gz == 0) continue;
            Room room = new Room(1, gx, gz, 0);
            assertFalse(hall.intersects(room.reservedBounds(alloc)), gx + "," + gz);
        }
        // a fully built nine-room archive: no room, reserved box or tunnel interior touches the hall.
        Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic");
        Direction[] around = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
        int next = 0;
        for (int source : new int[] {0, 1, 2, 3}) {
            for (Direction d : around) {
                if (a.rooms().size() >= 9) break;
                try { LayoutPlanner.commitAnnex(a, a.owner(), LayoutPlanner.previewAnnex(a, a.owner(), source, d, 9), 9); next++; } catch (ArchiveException ignored) { }
            }
        }
        assertTrue(a.rooms().size() >= 5);
        Bounds ah = Basement.shell(a.allocation());
        for (Room room : a.rooms().values()) assertFalse(ah.intersects(room.reservedBounds(a.allocation())));
        for (Bounds tunnel : LayoutPlanner.tunnelInteriors(a)) assertFalse(ah.intersects(tunnel));
    }

    @Test void chunkColumnsAreExactlyTheStarterRoomsFourColumnsAtEveryWidth() {
        // allocation origins are multiples of 8192, i.e. on a chunk corner: the 15-wide shell covers chunks (-1..0, -1..0) around the origin.
        Set<TicketPlanner.Chunk> hall = Basement.chunkColumns(alloc);
        assertEquals(4, hall.size());
        assertEquals(Set.of(new TicketPlanner.Chunk(-1, -1), new TicketPlanner.Chunk(0, -1), new TicketPlanner.Chunk(-1, 0), new TicketPlanner.Chunk(0, 0)), hall);
        for (int tier = 0; tier < 4; tier++) {
            Set<TicketPlanner.Chunk> room = new HashSet<>();
            Bounds b = new Room(0, 0, 0, tier).shell(alloc);
            for (int x = Math.floorDiv(b.minX(), 16); x <= Math.floorDiv(b.maxX(), 16); x++)
                for (int z = Math.floorDiv(b.minZ(), 16); z <= Math.floorDiv(b.maxZ(), 16); z++) room.add(new TicketPlanner.Chunk(x, z));
            assertTrue(room.containsAll(hall), "the hall adds no chunk column at width " + Room.widthOfTier(tier));
            assertEquals(4, room.size(), "the starter room is four chunk columns at every width");
        }
        // the ticket planner's per-archive count does not change when the hall is installed.
        Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic");
        int before = TicketPlanner.roomChunks(a).size();
        a.installBasement(a.owner());
        assertEquals(before, TicketPlanner.roomChunks(a).size());
        assertTrue(TicketPlanner.roomChunks(a).containsAll(Basement.chunkColumns(a.allocation())));
        assertTrue(Basement.chunkColumns(a.allocation()).size() <= ServerPolicy.DEFAULT.maxChunksPerArchive());
    }

    @Test void chunkAlignmentHoldsForEveryAllocationOrigin() {
        for (long id : new long[] {0, 1, 2, 5, 17, 100, 1000}) {
            Allocation a = Allocation.at(id);
            assertEquals(0, a.originX() % 16); assertEquals(0, a.originZ() % 16);
            assertEquals(4, Basement.chunkColumns(a).size(), "allocation " + id);
        }
    }

    @Test void liftPadsLieOnTheCentreLine() {
        assertEquals(new BlockPoint(alloc.originX(), 64, alloc.originZ()), Basement.upperPad(alloc));
        assertEquals(new BlockPoint(alloc.originX(), 56, alloc.originZ()), Basement.lowerPad(alloc));
        assertEquals(new BlockPoint(alloc.originX(), 65, alloc.originZ()), Basement.upperLanding(alloc), "the Tempad arrival cell");
        assertEquals(new BlockPoint(alloc.originX(), 57, alloc.originZ()), Basement.lowerLanding(alloc));
        // the upper pad is the starter room's floor centre at every size: an odd width has the same centre block.
        for (int tier = 0; tier < 4; tier++) {
            Bounds in = new Room(0, 0, 0, tier).interior(alloc);
            assertEquals(alloc.originX(), (in.minX() + in.maxX()) / 2);
            assertEquals(alloc.originZ(), (in.minZ() + in.maxZ()) / 2);
        }
    }

    @Test void twelveDistinctSlotsOnTheRingClearOfEverythingElse() {
        List<BlockPoint> slots = Basement.slots(alloc);
        assertEquals(12, slots.size());
        assertEquals(12, new HashSet<>(slots).size());
        Set<BlockPoint> pillars = new HashSet<>(Basement.pillars(alloc));
        assertEquals(4 * 6, pillars.size());
        for (int i = 0; i < slots.size(); i++) {
            BlockPoint s = slots.get(i);
            int u = s.x() - alloc.originX(), w = s.z() - alloc.originZ();
            assertEquals(4, Math.max(Math.abs(u), Math.abs(w)), "Chebyshev radius 4");
            assertEquals(57, s.y());
            assertFalse(Math.abs(u) <= 1 && Math.abs(w) <= 1, "not on the lift column");
            assertFalse(pillars.contains(s));
            assertNotEquals(s, Basement.terminal(alloc)); assertNotEquals(s, Basement.exit(alloc));
            assertTrue(Basement.interior(alloc).contains(s.x(), s.y(), s.z()));
            assertEquals(i, Basement.slotAt(alloc.originX(), alloc.originZ(), s.x(), s.y(), s.z()));
            assertEquals(i, Basement.slotAtNearest(s.x(), s.y(), s.z()));
            assertEquals(-1, Basement.slotAt(alloc.originX(), alloc.originZ(), s.x(), s.y() + 1, s.z()), "only the floor cell is a slot");
            assertEquals(Basement.Cell.SLOT, Basement.classify(u, 57, w));
            assertEquals(Basement.Cell.SLOT, Basement.classify(u, 56, w), "the pedestal base");
        }
        // two free cells between neighbours on a side (room for a label and a door)
        assertEquals(-3, Basement.slots(alloc).get(0).x() - alloc.originX());
        assertEquals(3, Basement.slots(alloc).get(2).x() - alloc.originX());
        for (BlockPoint p : pillars) assertEquals(Basement.Cell.PILLAR, Basement.classify(p.x() - alloc.originX(), p.y(), p.z() - alloc.originZ()));
        assertEquals(Basement.Cell.TERMINAL, Basement.classify(0, 57, -6));
        assertEquals(Basement.Cell.EXIT, Basement.classify(-1, 57, -6));
        assertEquals(Basement.Cell.EXIT, Basement.classify(-1, 58, -6), "the upper half of the door");
        assertEquals(Basement.exit(alloc).y() + 1, Basement.exitUpper(alloc).y());
        assertTrue(Basement.exitUpper(alloc).y() <= Basement.INTERIOR_MAX_Y - 2, "the door leaves headroom under the ceiling");
        assertEquals(Basement.Cell.FREE, Basement.classify(-1, 59, -6));
        assertEquals(Basement.Cell.LIFT_COLUMN, Basement.classify(0, 60, 1));
        assertEquals(Basement.Cell.DAIS, Basement.classify(1, 56, 1));
        assertEquals(Basement.Cell.SHELL, Basement.classify(7, 60, 0));
        assertEquals(Basement.Cell.FREE, Basement.classify(2, 60, 2));
        assertEquals(Basement.Cell.FREE, Basement.classify(9, 60, 0));
    }

    @Test void slotsFaceTheLift() {
        for (int i = 0; i < 12; i++) {
            BlockPoint s = Basement.slot(alloc, i);
            Direction f = Basement.slotFacing(i);
            int u = s.x() - alloc.originX(), w = s.z() - alloc.originZ();
            // stepping one block the way the pad faces gets closer to the centre
            assertTrue(Math.abs(u + f.dx) + Math.abs(w + f.dz) < Math.abs(u) + Math.abs(w), "slot " + i);
        }
    }


    @Test void placementRules() {
        int cx = alloc.originX(), cz = alloc.originZ();
        assertEquals(Basement.Placement.OK, Basement.placement(alloc, cx + 4, 57, cz, true), "a pad on a slot");
        assertEquals(Basement.Placement.PAD_ONLY_ON_SLOTS, Basement.placement(alloc, cx + 2, 57, cz, true));
        assertEquals(Basement.Placement.PAD_ONLY_ON_SLOTS, Basement.placement(alloc, cx + 4, 58, cz, true), "not above the floor cell");
        assertEquals(Basement.Placement.PAD_ONLY_ON_SLOTS, Basement.placement(alloc, cx + 100, 64, cz, true), "nowhere outside the hall");
        assertEquals(Basement.Placement.SLOT_ONLY_PADS, Basement.placement(alloc, cx + 4, 57, cz, false));
        assertEquals(Basement.Placement.LIFT_COLUMN, Basement.placement(alloc, cx, 57, cz, false));
        assertEquals(Basement.Placement.LIFT_COLUMN, Basement.placement(alloc, cx + 1, 62, cz - 1, false));
        assertEquals(Basement.Placement.OK, Basement.placement(alloc, cx + 2, 57, cz, false), "ordinary blocks elsewhere in the hall");
        assertEquals(Basement.Placement.OK, Basement.placement(alloc, cx, 66, cz, false), "the starter room above is untouched");
    }

    @Test void onlyTheOwnerAndOperatorsMayUseTheLift() {
        Archive a = installed();
        UUID guest = UUID.randomUUID();
        AccessControl.invite(a, a.owner(), guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER, Permission.STORAGE, Permission.INTERACT_BLOCK, Permission.BUILD,
            Permission.INTERACT_ENTITY, Permission.SET_SPAWN), 1_000L, ServerPolicy.DEFAULT);
        HubPolicy on = HubPolicy.DEFAULT, off = new HubPolicy(false, 12, 20, 100, true);
        assertTrue(Basement.mayUse(a, a.owner(), false, ServerPolicy.DEFAULT, on));
        assertFalse(Basement.mayUse(a, guest, false, ServerPolicy.DEFAULT, on), "a guest with every delegable right still may not");
        assertFalse(Basement.mayUse(a, UUID.randomUUID(), false, ServerPolicy.DEFAULT, on), "a stranger may not");
        assertTrue(Basement.mayUse(a, guest, true, ServerPolicy.DEFAULT, on), "an operator may");
        assertFalse(Basement.mayUse(a, a.owner(), false, ServerPolicy.DEFAULT, off), "the server veto stops the owner");
        assertFalse(Basement.mayUse(a, a.owner(), true, ServerPolicy.DEFAULT, off), "and operators");
        assertFalse(Basement.mayUse(archive, owner, false, ServerPolicy.DEFAULT, on), "no hall installed, nothing to use");
        assertThrows(ArchiveSecurityException.class, () -> a.addDestination(guest, "Home", "minecraft:overworld", 0, 64, 0, 0, "", 12), "mutators are owner-only");
    }


    @Test void destinationsNeedTheHall() {
        assertThrows(ArchiveException.class, () -> archive.addDestination(owner, "Home", "minecraft:overworld", 0, 64, 0, 0, "", 12));
        Archive a = installed();
        Destination d = a.addDestination(a.owner(), " Home Base ", "minecraft:overworld", 10.5, 70, -3.5, 90, "minecraft:grass_block", 12);
        assertEquals("Home Base", d.name(), "names are trimmed");
        assertEquals(1, d.id());
        assertEquals(d, a.basement().destination(1).orElseThrow());
    }

    @Test void nameAndPlaceValidation() {
        Archive a = installed();
        String ok = "minecraft:overworld";
        for (String bad : new String[] {"", "   ", "x".repeat(33), "bad\nname", "<b>x</b>", "tab\there", "\u0007bell", "semi;colon", "slash/x"})
            assertThrows(ArchiveException.class, () -> a.addDestination(a.owner(), bad, ok, 0, 64, 0, 0, "", 12), "name: " + bad.replace("\n", "\\n"));
        assertDoesNotThrow(() -> a.addDestination(a.owner(), "Nether (hub) 2", "minecraft:the_nether", 8, 70, 8, 0, "", 12));
        assertDoesNotThrow(() -> a.addDestination(a.owner(), "Schöne Aussicht", ok, 0, 64, 0, 0, "", 12), "letters of any language");
        assertDoesNotThrow(() -> a.addDestination(a.owner(), "x".repeat(32), ok, 0, 64, 0, 0, "", 12));
        for (double[] bad : new double[][] {{Double.NaN, 64, 0}, {0, Double.POSITIVE_INFINITY, 0}, {0, 64, 3.0e7}, {-3.0e7, 64, 0}, {0, 5000, 0}, {0, -3000, 0}})
            assertThrows(ArchiveException.class, () -> a.addDestination(a.owner(), "P" + Math.abs(bad[0] + bad[1] + bad[2]), ok, bad[0], bad[1], bad[2], 0, "", 12));
        assertThrows(ArchiveException.class, () -> a.addDestination(a.owner(), "Yaw", ok, 0, 64, 0, Float.NaN, "", 12));
        for (String dim : new String[] {"", "overworld", "Minecraft:Overworld", "a b:c", "x:"}) assertThrows(ArchiveException.class, () -> a.addDestination(a.owner(), "D" + dim.length(), dim, 0, 64, 0, 0, "", 12), dim);
        assertThrows(ArchiveException.class, () -> a.addDestination(a.owner(), "Icon", ok, 0, 64, 0, 0, "Not An Item", 12));
        assertEquals("error.nullspace.destination.bad_name", assertThrows(ArchiveException.class, () -> a.addDestination(a.owner(), "", ok, 0, 64, 0, 0, "", 12)).key());
    }

    @Test void capAndDuplicates() {
        Archive a = installed();
        for (int i = 0; i < 12; i++) a.addDestination(a.owner(), "Place " + i, "minecraft:overworld", i, 64, 0, 0, "", 12);
        ArchiveException full = assertThrows(ArchiveException.class, () -> a.addDestination(a.owner(), "One more", "minecraft:overworld", 0, 64, 0, 0, "", 12));
        assertEquals("error.nullspace.destination.limit", full.key());
        assertEquals(12, full.args()[0]);
        assertDoesNotThrow(() -> a.addDestination(a.owner(), "One more", "minecraft:overworld", 0, 64, 0, 0, "", 13), "a higher configured cap lets one more in");
        assertThrows(ArchiveException.class, () -> a.addDestination(a.owner(), "place 3", "minecraft:overworld", 0, 64, 0, 0, "", 40), "names are unique, ignoring case");
        // the cap cannot be raised past the hard ceiling by configuration
        Archive b = installed();
        for (int i = 0; i < Basement.MAX_DESTINATIONS; i++) b.addDestination(b.owner(), "N" + i, "minecraft:overworld", i, 64, 0, 0, "", 1000);
        assertThrows(ArchiveException.class, () -> b.addDestination(b.owner(), "N-over", "minecraft:overworld", 0, 64, 0, 0, "", 1000));
        // a cap lowered below the current number keeps what is there but accepts nothing new
        assertThrows(ArchiveException.class, () -> a.addDestination(a.owner(), "Late", "minecraft:overworld", 0, 64, 0, 0, "", 2));
        assertEquals(13, a.basement().destinations().size());
    }

    @Test void renameDeleteAndBind() {
        Archive a = installed();
        UUID o = a.owner();
        Destination home = a.addDestination(o, "Home", "minecraft:overworld", 0, 64, 0, 0, "", 12);
        Destination mine = a.addDestination(o, "Mine", "minecraft:the_nether", 8, 70, 8, 0, "", 12);
        assertThrows(ArchiveException.class, () -> a.renameDestination(o, mine.id(), "home"), "rename onto an existing name");
        assertEquals("Mine", a.basement().destination(mine.id()).orElseThrow().name(), "a refused rename changes nothing");
        a.renameDestination(o, mine.id(), "Deep Mine");
        assertEquals("Deep Mine", a.basement().destination(mine.id()).orElseThrow().name());
        assertEquals("minecraft:the_nether", a.basement().destination(mine.id()).orElseThrow().dimension());
        assertDoesNotThrow(() -> a.renameDestination(o, mine.id(), "deep mine"), "renaming to its own name in another case is fine");
        assertThrows(ArchiveException.class, () -> a.renameDestination(o, 99, "Nope"));

        a.bindPad(o, 3, home.id());
        a.bindPad(o, 4, mine.id());
        assertEquals(home, a.basement().destinationOfSlot(3).orElseThrow());
        assertEquals(3, a.basement().slotOf(home.id()));
        a.bindPad(o, 7, home.id());
        assertTrue(a.basement().destinationOfSlot(3).isEmpty(), "binding a destination to another pad moves it");
        assertEquals(7, a.basement().slotOf(home.id()));
        a.bindPad(o, 4, home.id());
        assertEquals(Set.of(4), a.basement().bindings().keySet(), "the slot's old destination is replaced, and Home moved");
        assertThrows(ArchiveException.class, () -> a.bindPad(o, 12, home.id()));
        assertThrows(ArchiveException.class, () -> a.bindPad(o, -1, home.id()));
        assertThrows(ArchiveException.class, () -> a.bindPad(o, 0, 99));
        a.removeDestination(o, home.id());
        assertTrue(a.basement().bindings().isEmpty(), "deleting a destination unbinds its pad");
        assertEquals(1, a.basement().destinations().size());
        Destination again = a.addDestination(o, "Home", "minecraft:overworld", 0, 64, 0, 0, "", 12);
        assertNotEquals(home.id(), again.id(), "ids are never reused, so a stale binding can never point at a new destination");
        a.unbindPad(o, 4); a.unbindPad(o, 4);
    }


    @Test void restoreKeepsValidPartsAndDropsTheRest() {
        Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic");
        Destination d1 = new Destination(1, "One", "minecraft:overworld", 1, 64, 1, 0, ""), d2 = new Destination(2, "Two", "minecraft:the_end", 1, 64, 1, 0, "");
        Destination dup = new Destination(1, "Dup id", "minecraft:overworld", 1, 64, 1, 0, ""), dupName = new Destination(5, "one", "minecraft:overworld", 1, 64, 1, 0, "");
        Map<Integer, Integer> bindings = new LinkedHashMap<>();
        bindings.put(0, 1); bindings.put(1, 2); bindings.put(2, 99); bindings.put(40, 1); bindings.put(3, 1);
        int dropped = a.restoreBasement(true, 1, List.of(d1, d2, dup, dupName), bindings);
        assertTrue(a.basement().installed());
        assertEquals(2, a.basement().destinations().size());
        assertEquals(Set.of(0, 1), a.basement().bindings().keySet(), "unknown destination, bad slot and a second pad for the same destination are dropped");
        assertEquals(2 + 3, dropped);
        assertEquals(3, a.basement().nextDestinationId(), "the counter is repaired to stay above every id");
        // an empty / default restore is a clean, not-installed hall
        Archive b = registry.claim(UUID.randomUUID(), "nullspace:classic");
        assertFalse(b.basement().installed());
        assertEquals(0, b.restoreBasement(false, 0, List.of(), Map.of()));
        assertEquals(1, b.basement().nextDestinationId());
        assertTrue(b.basement().destinations().isEmpty());
    }

    @Test void restoreWithTooManyDestinationsKeepsTheCeiling() {
        Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic");
        List<Destination> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) many.add(new Destination(i + 1, "Spot " + i, "minecraft:overworld", i, 64, 0, 0, ""));
        a.restoreBasement(true, 1, many, Map.of());
        assertEquals(Basement.MAX_DESTINATIONS, a.basement().destinations().size());
    }

    @Test void installingTwiceIsIdempotent() {
        Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic");
        a.installBasement(a.owner()); a.installBasement(a.owner());
        assertTrue(a.basement().installed());
        assertThrows(ArchiveSecurityException.class, () -> a.installBasement(UUID.randomUUID()));
    }

    @Test void theHallIsNotARoom() {
        Archive a = installed();
        assertEquals(1, a.rooms().size(), "installing the hall adds no room");
        // all nine rooms can still be annexed with the hall installed
        for (int i = 0; i < 8; i++) {
            Direction[] ds = Direction.values();
            boolean added = false;
            for (int source : a.rooms().keySet()) {
                for (Direction d : ds) {
                    try { LayoutPlanner.commitAnnex(a, a.owner(), LayoutPlanner.previewAnnex(a, a.owner(), source, d, 9), 9); added = true; break; } catch (ArchiveException ignored) { }
                }
                if (added) break;
            }
            assertTrue(added, "annex " + i);
        }
        assertEquals(9, a.rooms().size());
        assertTrue(a.basement().installed());
    }


    @Test void pendingEditKind() {
        PendingEdit edit = PendingEdit.basement();
        assertEquals(PendingEdit.Kind.BASEMENT, edit.kind());
        Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic");
        assertFalse(edit.alreadyApplied(a), "not installed: the world must be rolled back");
        a.installBasement(a.owner());
        assertTrue(edit.alreadyApplied(a), "installed and recorded: nothing to roll back");
    }


    @Test void holdFiresOnceAfterTheConfiguredTicks() {
        HoldCharge hold = new HoldCharge();
        UUID p = UUID.randomUUID();
        for (int i = 1; i < 20; i++) assertEquals(HoldCharge.Result.CHARGING, hold.step(p, true, 20), "tick " + i);
        assertEquals(0.95f, hold.progress(p, 20), 0.001f);
        assertEquals(HoldCharge.Result.FIRED, hold.step(p, true, 20), "the 20th tick of holding");
        for (int i = 0; i < 100; i++) assertEquals(HoldCharge.Result.IDLE, hold.step(p, true, 20), "holding on does not ride again");
        assertEquals(0f, hold.progress(p, 20));
        assertEquals(HoldCharge.Result.IDLE, hold.step(p, false, 20));
        assertEquals(HoldCharge.Result.CHARGING, hold.step(p, true, 20), "after letting go a new charge starts from zero");
    }

    @Test void releasingEarlyResetsTheCharge() {
        HoldCharge hold = new HoldCharge();
        UUID p = UUID.randomUUID();
        for (int i = 0; i < 19; i++) hold.step(p, true, 20);
        assertEquals(HoldCharge.Result.IDLE, hold.step(p, false, 20), "one tick of not holding empties it");
        assertEquals(0f, hold.progress(p, 20));
        for (int i = 1; i < 20; i++) assertEquals(HoldCharge.Result.CHARGING, hold.step(p, true, 20));
        assertEquals(HoldCharge.Result.FIRED, hold.step(p, true, 20));
    }

    @Test void playersChargeIndependentlyAndTheConfiguredLengthApplies() {
        HoldCharge hold = new HoldCharge();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (int i = 0; i < 4; i++) { hold.step(a, true, 5); }
        assertEquals(HoldCharge.Result.CHARGING, hold.step(b, true, 5));
        assertEquals(HoldCharge.Result.FIRED, hold.step(a, true, 5));
        assertEquals(1, hold.tracked() - 1, "b is still charging");
        assertEquals(HoldCharge.Result.FIRED, fire(hold, b, 5, 4));
        hold.clear(a); hold.clear(b);
        assertEquals(0, hold.tracked());
        hold.step(a, true, 5); hold.retainOnly(Set.of());
        assertEquals(0, hold.tracked());
        assertThrows(ArchiveException.class, () -> hold.step(a, true, 0));
    }

    private static HoldCharge.Result fire(HoldCharge hold, UUID p, int needed, int more) {
        HoldCharge.Result r = HoldCharge.Result.IDLE;
        for (int i = 0; i < more; i++) r = hold.step(p, true, needed);
        return r;
    }

    @Test void hubPolicyClampsBadConfiguration() {
        HubPolicy bad = new HubPolicy(true, -5, 0, -1, true);
        assertEquals(1, bad.maxDestinations()); assertEquals(HubPolicy.MIN_HOLD, bad.liftHoldTicks()); assertEquals(0, bad.padCooldownTicks());
        HubPolicy huge = new HubPolicy(true, 9999, 99999, 99999999, false);
        assertEquals(HubPolicy.MAX_DESTINATIONS, huge.maxDestinations()); assertEquals(HubPolicy.MAX_HOLD, huge.liftHoldTicks()); assertEquals(HubPolicy.MAX_COOLDOWN, huge.padCooldownTicks());
    }

    @Test void destinationLabelIsTruncated() {
        Destination d = new Destination(1, "A very long destination name 12", "minecraft:overworld", 0, 64, 0, 0, "");
        assertEquals(Destination.LABEL_CHARS, d.label().length());
        assertTrue(d.label().endsWith("…"));
        assertEquals("Home", new Destination(1, "Home", "minecraft:overworld", 0, 64, 0, 0, "").label());
    }
}
