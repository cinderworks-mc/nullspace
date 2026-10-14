package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessAndLayoutTest {
    private static final ServerPolicy POLICY = ServerPolicy.DEFAULT;
    private static final ReturnLocation HOME = new ReturnLocation("minecraft:overworld", 10, 70, -5, 90, 0);
    private final UUID owner = UUID.randomUUID();
    private final UUID guest = UUID.randomUUID();
    private final ArchiveRegistry registry = new ArchiveRegistry();

    private Archive archive() { return registry.claim(owner, "nullspace:classic"); }

    @Test void aGuestWhoDiedElsewhereMayRespawnInsideOnlyWithEntryAndTheSpawnRightAndNoVeto() {
        Archive a = archive();
        assertTrue(AccessControl.invitedSpawnRight(a, owner, POLICY), "the owner always may (unless vetoed)");
        assertFalse(AccessControl.invitedSpawnRight(a, guest, POLICY), "not invited");
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, EnumSet.of(Permission.ENTER), 0, POLICY);
        assertFalse(AccessControl.invitedSpawnRight(a, guest, POLICY), "ENTER only");
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, EnumSet.of(Permission.ENTER, Permission.SET_SPAWN), 0, POLICY);
        assertTrue(AccessControl.invitedSpawnRight(a, guest, POLICY));
        assertTrue(AccessControl.mayEnter(a, guest, 0, POLICY));
        assertFalse(AccessControl.can(a, guest, Permission.SET_SPAWN, 0, POLICY), "without a visit the live check fails, which is why the respawn needs invitedSpawnRight");
        a.updateSettings(owner, new ArchiveSettings(false, false, false));
        assertFalse(AccessControl.invitedSpawnRight(a, guest, POLICY), "the archive's own switch");
        assertFalse(AccessControl.invitedSpawnRight(a, owner, POLICY));
    }

    @Test void claimingTwiceReturnsSameArchiveAndNeverReallocates() {
        Archive first = archive();
        assertSame(first, registry.claim(owner, "nullspace:create"));
        assertEquals("nullspace:classic", first.starterPreset());
        assertEquals(1, registry.nextAllocationId());
    }

    @Test void allocationsAreUniqueAndMaxSizeRoomsNeverOverlapAcrossOwners() {
        Set<Long> seen = new HashSet<>();
        Archive[] archives = new Archive[50];
        for (int i = 0; i < archives.length; i++) archives[i] = registry.claim(UUID.randomUUID(), "nullspace:classic");
        for (Archive a : archives) assertTrue(seen.add(a.allocationId()));
        for (Archive a : archives) for (Archive b : archives) if (a != b)
            assertFalse(a.room(0).reservedBounds(a.allocation()).intersects(b.room(0).reservedBounds(b.allocation())));
    }

    @Test void everyRoomInAFullArchiveFitsItsReservationAtMaximumSize() {
        Archive a = archive();
        Direction[] order = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
        int added = 0;
        for (int source = 0; added < 8 && source < a.rooms().size(); source++) {
            for (Direction d : order) {
                if (added >= 8) break;
                try { LayoutPlanner.commitAnnex(a, owner, LayoutPlanner.previewAnnex(a, owner, source, d, 9), 9); added++; }
                catch (IllegalArgumentException blocked) { /* occupied neighbour */ }
            }
        }
        assertEquals(9, a.rooms().size());
        for (Room room : a.rooms().values()) {
            Bounds max = room.bounds(a.allocation(), Room.MAX_WIDTH, true);
            assertTrue(Math.abs(max.minX() - a.allocation().originX()) <= Allocation.BUILD_RADIUS);
            assertTrue(Math.abs(max.maxZ() - a.allocation().originZ()) <= Allocation.BUILD_RADIUS);
            for (Room other : a.rooms().values()) if (other != room)
                assertFalse(max.intersects(other.bounds(a.allocation(), Room.MAX_WIDTH, true)));
        }
        assertThrows(IllegalArgumentException.class, () -> LayoutPlanner.previewAnnex(a, owner, 0, Direction.NORTH, 9));
    }

    @Test void enlargingOnlyByOwnerStopsAtSeventeenExceptTheStarterHallAndNeverTouchesNeighbours() {
        Archive a = archive();
        LayoutPlanner.commitAnnex(a, owner, LayoutPlanner.previewAnnex(a, owner, 0, Direction.EAST, 9), 9);
        assertThrows(SecurityException.class, () -> LayoutPlanner.previewExpansion(a, guest, 0));
        for (int round = 0; round < 2; round++) for (int id : new int[] {0, 1}) {
            LayoutPlanner.commitExpansion(a, owner, LayoutPlanner.previewExpansion(a, owner, id));
        }
        assertEquals(17, a.room(0).width());
        assertEquals(17, a.room(1).width());
        // only the starter room can become a 21 wide Grand Hall; every other room stops at 17.
        assertThrows(IllegalArgumentException.class, () -> LayoutPlanner.previewExpansion(a, owner, 1));
        LayoutPlanner.commitExpansion(a, owner, LayoutPlanner.previewExpansion(a, owner, 0));
        assertEquals(21, a.room(0).width());
        assertEquals(17, a.room(1).width());
        assertThrows(IllegalArgumentException.class, () -> LayoutPlanner.previewExpansion(a, owner, 0));
    }

    @Test void stalePreviewsAreRejected() {
        Archive a = archive();
        var preview = LayoutPlanner.previewExpansion(a, owner, 0);
        LayoutPlanner.commitExpansion(a, owner, preview);
        assertThrows(IllegalArgumentException.class, () -> LayoutPlanner.commitExpansion(a, owner, preview));
    }

    @Test void expansionNeverFillsAConnectedTunnelWithShell() {
        Archive a = archive();
        LayoutPlanner.commitAnnex(a, owner, LayoutPlanner.previewAnnex(a, owner, 0, Direction.EAST, 9), 9);
        var plan = LayoutPlanner.previewExpansion(a, owner, 0);
        assertFalse(plan.addShell().isEmpty());
        for (BlockPoint p : plan.addShell()) assertFalse(LayoutPlanner.inConnectedPassage(a, a.room(0), p));
    }

    @Test void roomLimitAndDuplicateDoorwaysAreRejected() {
        Archive a = archive();
        LayoutPlanner.commitAnnex(a, owner, LayoutPlanner.previewAnnex(a, owner, 0, Direction.EAST, 2), 2);
        assertThrows(IllegalArgumentException.class, () -> LayoutPlanner.previewAnnex(a, owner, 0, Direction.NORTH, 2));
        assertThrows(IllegalArgumentException.class, () -> LayoutPlanner.previewAnnex(a, owner, 0, Direction.EAST, 9));
    }

    @Test void newGuestsMayOnlyEnterAndObserve() {
        Archive a = archive();
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Invitation.observerPermissions(), 0, POLICY);
        AccessControl.enter(a, guest, HOME, 1, POLICY);
        assertTrue(AccessControl.can(a, guest, Permission.ENTER, 0, POLICY));
        for (Permission p : Permission.values()) if (p != Permission.ENTER) assertFalse(AccessControl.can(a, guest, p, 0, POLICY), p.name());
    }

    @Test void strangersAndUnauthorizedCallersAreRefused() {
        Archive a = archive();
        UUID stranger = UUID.randomUUID();
        assertFalse(AccessControl.mayEnter(a, stranger, 0, POLICY));
        assertThrows(SecurityException.class, () -> AccessControl.enter(a, stranger, HOME, 0, POLICY));
        assertThrows(SecurityException.class, () -> AccessControl.invite(a, guest, stranger, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, POLICY));
        assertThrows(SecurityException.class, () -> AccessControl.remove(a, guest, owner));
        assertThrows(SecurityException.class, () -> a.savePreset(guest, "x", Set.of(Permission.ENTER)));
        assertThrows(SecurityException.class, () -> a.installAnchor(guest));
        assertFalse(AccessControl.can(a, stranger, Permission.ENTER, 0, POLICY));
    }

    @Test void guestsCanNeverBeGrantedOrElevateToAdmin() {
        Archive a = archive();
        assertThrows(IllegalArgumentException.class, () -> AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, EnumSet.of(Permission.ENTER, Permission.ADMIN), 0, POLICY));
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, POLICY);
        AccessControl.enter(a, guest, HOME, 0, POLICY);
        assertThrows(SecurityException.class, () -> AccessControl.setPermissions(a, guest, guest, Set.of(Permission.ENTER, Permission.BUILD), POLICY));
        assertFalse(AccessControl.can(a, guest, Permission.ADMIN, 0, POLICY));
        assertTrue(AccessControl.can(a, owner, Permission.ADMIN, 0, POLICY));
    }

    @Test void changingPermissionsUpdatesLiveRightsWithoutResettingExpiry() {
        Archive a = archive();
        AccessControl.invite(a, owner, guest, Invitation.Kind.TIMED, 1000, Set.of(Permission.ENTER), 0, POLICY);
        AccessControl.enter(a, guest, HOME, 1, POLICY);
        AccessControl.setPermissions(a, owner, guest, Set.of(Permission.ENTER, Permission.BUILD), POLICY);
        assertTrue(AccessControl.can(a, guest, Permission.BUILD, 0, POLICY));
        assertEquals(1000, a.invitations().get(guest).expiresAtMillis());
    }

    @Test void timedAccessEndsAtExpiryForVisitorsInsideAndForReentry() {
        Archive a = archive();
        AccessControl.invite(a, owner, guest, Invitation.Kind.TIMED, 1000, Set.of(Permission.ENTER, Permission.STORAGE), 0, POLICY);
        assertTrue(AccessControl.mayEnter(a, guest, 999, POLICY));
        AccessControl.enter(a, guest, HOME, 500, POLICY);
        assertFalse(AccessControl.mayEnter(a, guest, 1000, POLICY));
        assertTrue(AccessControl.can(a, guest, Permission.STORAGE, 999, POLICY));
        assertFalse(AccessControl.can(a, guest, Permission.STORAGE, 1000, POLICY), "the rights stop at the moment of expiry");
        assertEquals(HOME, AccessControl.exit(a, guest).orElseThrow());
        assertFalse(AccessControl.mayEnter(a, guest, 1001, POLICY));
        assertFalse(AccessControl.can(a, guest, Permission.STORAGE, 0, POLICY));
    }

    @Test void singleVisitIsConsumedOnCrossingNotOnOpeningAndReentryKeepsOriginalReturn() {
        Archive a = archive();
        AccessControl.invite(a, owner, guest, Invitation.Kind.SINGLE_VISIT, 0, Set.of(Permission.ENTER), 0, POLICY);
        assertTrue(AccessControl.mayEnter(a, guest, 1, POLICY));
        assertTrue(AccessControl.mayEnter(a, guest, 2, POLICY), "authorizing alone must not consume");
        Visit first = AccessControl.enter(a, guest, HOME, 3, POLICY);
        ReturnLocation other = new ReturnLocation("minecraft:the_nether", 1, 2, 3, 0, 0);
        assertSame(first, AccessControl.enter(a, guest, other, 4, POLICY));
        assertEquals(HOME, first.returnLocation());
        AccessControl.exit(a, guest);
        assertFalse(AccessControl.mayEnter(a, guest, 5, POLICY));
    }

    @Test void removalRevokesInviteReturnsLocationAndOwnerCannotBeRemoved() {
        Archive a = archive();
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER, Permission.BUILD), 0, POLICY);
        AccessControl.enter(a, guest, HOME, 0, POLICY);
        assertEquals(HOME, AccessControl.remove(a, owner, guest).orElseThrow());
        assertFalse(AccessControl.mayEnter(a, guest, 1, POLICY));
        assertFalse(AccessControl.can(a, guest, Permission.BUILD, 0, POLICY));
        assertThrows(IllegalArgumentException.class, () -> AccessControl.remove(a, owner, owner));
    }

    @Test void serverPolicyAlwaysWinsOverOwnerChoices() {
        Archive a = archive();
        ServerPolicy strict = new ServerPolicy(false, false, false, false, 0, 1, 9, 1, 1000, Set.of(Permission.ENTER));
        assertThrows(IllegalArgumentException.class, () -> AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER, Permission.BUILD), 0, strict));
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, strict);
        assertThrows(IllegalArgumentException.class, () -> AccessControl.invite(a, owner, UUID.randomUUID(), Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, strict), "guest limit");
        assertThrows(IllegalArgumentException.class, () -> AccessControl.invite(a, owner, guest, Invitation.Kind.TIMED, 5000, Set.of(Permission.ENTER), 0, strict), "duration cap");
        a.updateSettings(owner, new ArchiveSettings(true, true, true));
        assertEquals(new ArchiveSettings(false, false, false), strict.effective(a.settings()));
        assertFalse(AccessControl.can(a, owner, Permission.SET_SPAWN, 0, strict), "spawn veto applies to the owner too");
    }

    @Test void anchorTicketsAreBoundedAndReleasedWhenDisabledOrOccupied() {
        Archive a = archive(); a.markInitialized(); a.installAnchor(owner);
        var on = TicketPlanner.desired(registry.archives().values(), Set.of(), POLICY);
        assertFalse(on.isEmpty());
        assertTrue(TicketPlanner.desired(registry.archives().values(), Set.of(owner), POLICY).isEmpty(), "occupied archives use player tickets");
        ServerPolicy off = new ServerPolicy(false, false, true, false, 16, 256, 9, 32, 1000, Set.of(Permission.ENTER));
        var change = TicketPlanner.transition(on, TicketPlanner.desired(registry.archives().values(), Set.of(), off));
        assertEquals(on, change.release());
        assertTrue(change.acquire().isEmpty());
        ServerPolicy tiny = new ServerPolicy(false, false, true, true, 16, 1, 9, 32, 1000, Set.of(Permission.ENTER));
        assertTrue(TicketPlanner.desired(registry.archives().values(), Set.of(), tiny).isEmpty(), "over-budget archives get no partial activation");
    }

    @Test void visitsSurviveRestoreAndInvalidReturnLocationsAreRejected() {
        Archive a = archive(); a.markInitialized();
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, POLICY);
        AccessControl.enter(a, guest, HOME, 7, POLICY);
        Archive copy = Archive.restore(a.owner(), a.allocation(), a.starterPreset(), a.initialized(), a.anchorInstalled(), a.settings(),
            a.rooms(), a.invitations(), a.activeVisits(), a.presets());
        assertEquals(HOME, copy.activeVisits().get(guest).returnLocation());
        assertThrows(IllegalArgumentException.class, () -> new ReturnLocation("nonsense", 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReturnLocation("minecraft:overworld", Double.NaN, 0, 0, 0, 0));
    }

    @Test void cosmeticFinishesAreIdentifiersOnlyAndWholeSurfaceChangeClearsOverrides() {
        Room room = archive().room(0);
        room.setSurfaceFinish(Surface.NORTH, "nullspace:green");
        room.setIndividualFinish(new Room.FinishCoordinate(Surface.NORTH, 1, 2), "nullspace:amber");
        assertEquals("nullspace:amber", room.finish(new Room.FinishCoordinate(Surface.NORTH, 1, 2)));
        assertEquals("nullspace:green", room.finish(new Room.FinishCoordinate(Surface.NORTH, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> room.setSurfaceFinish(Surface.NORTH, "not an id"));
        room.setSurfaceFinish(Surface.NORTH, "nullspace:ivory");
        assertTrue(room.individualFinishes().isEmpty());
    }
}
