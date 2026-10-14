package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// attacks on the pure access model. tests marked SR-n showed a real defect before its fix
class SecurityReviewTest {
    private static final ServerPolicy POLICY = ServerPolicy.DEFAULT;
    private static final ReturnLocation HOME = new ReturnLocation("minecraft:overworld", 0, 70, 0, 0, 0);
    private final UUID owner = UUID.randomUUID(), guest = UUID.randomUUID(), stranger = UUID.randomUUID();

    private Archive archive() { return new ArchiveRegistry().claim(owner, "nullspace:classic"); }

    // SR-8: revoking an invitation used to move any online player standing in the archive dimension
    @Test void onlyARealVisitorOfThisArchiveIsEverMoved() {
        for (boolean online : new boolean[] {true, false}) for (boolean inDimension : new boolean[] {true, false}) {
            assertEquals(AccessControl.Departure.NONE, AccessControl.departure(Optional.empty(), online, inDimension), "no visit: nothing moves (online=" + online + ", inDimension=" + inDimension + ")");
        }
        assertEquals(AccessControl.Departure.EVICT_NOW, AccessControl.departure(Optional.of(HOME), true, true));
        assertEquals(AccessControl.Departure.RETURN_AT_LOGIN, AccessControl.departure(Optional.of(HOME), true, false));
        assertEquals(AccessControl.Departure.RETURN_AT_LOGIN, AccessControl.departure(Optional.of(HOME), false, false));
        assertEquals(AccessControl.Departure.RETURN_AT_LOGIN, AccessControl.departure(Optional.of(HOME), false, true));
    }

    // SR-8: removing someone who is neither guest nor visitor used to report success
    @Test void removingANonGuestIsRefused() {
        Archive a = archive();
        ArchiveException e = assertThrows(ArchiveException.class, () -> AccessControl.remove(a, owner, stranger));
        assertEquals("error.nullspace.invite.not_guest", e.key());
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, POLICY);
        assertTrue(AccessControl.remove(a, owner, guest).isEmpty(), "a guest who never entered has no visit to end");
        assertTrue(a.invitations().isEmpty());
        // a visitor without a standing invitation (respawned into the archive) can still be removed.
        AccessControl.invite(a, owner, guest, Invitation.Kind.SINGLE_VISIT, 0, Set.of(Permission.ENTER), 0, POLICY);
        AccessControl.enter(a, guest, HOME, 1, POLICY);
        a.removeInvitation(guest);
        assertEquals(Optional.of(HOME), AccessControl.remove(a, owner, guest));
    }

    @Test void onlyTheOwnerMayRemoveAnyone() {
        Archive a = archive();
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, POLICY);
        assertThrows(ArchiveSecurityException.class, () -> AccessControl.remove(a, guest, guest));
        assertThrows(ArchiveSecurityException.class, () -> AccessControl.remove(a, stranger, guest));
    }


    @Test void adminCanNeverBeGrantedByAnyRoute() {
        Archive a = archive();
        Set<Permission> withAdmin = EnumSet.of(Permission.ENTER, Permission.ADMIN);
        assertThrows(ArchiveException.class, () -> AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, withAdmin, 0, POLICY));
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, POLICY);
        assertThrows(ArchiveException.class, () -> AccessControl.setPermissions(a, owner, guest, withAdmin, POLICY));
        assertThrows(ArchiveException.class, () -> a.savePreset(owner, "x", withAdmin));
        AccessControl.enter(a, guest, HOME, 1, POLICY);
        assertFalse(AccessControl.can(a, guest, Permission.ADMIN, 0, POLICY));
        assertThrows(ArchiveSecurityException.class, () -> a.installAnchor(guest));
        assertThrows(ArchiveSecurityException.class, () -> a.updateSettings(guest, ArchiveSettings.DEFAULT));
        assertThrows(ArchiveSecurityException.class, () -> a.savePreset(guest, "x", Set.of()));
    }

    @Test void hostilePersistedStateIsRejected() {
        Allocation allocation = Allocation.at(0);
        Map<Integer, Room> rooms = Map.of(0, new Room(0, 0, 0, 0));
        Visit adminGuest = new Visit(HOME, 0, Invitation.allPermissions());
        assertThrows(ArchiveException.class, () -> Archive.restore(owner, allocation, "nullspace:classic", true, false, ArchiveSettings.DEFAULT, rooms, Map.of(), Map.of(guest, adminGuest), Map.of()));
        Invitation self = new Invitation(Invitation.Kind.PERMANENT, 0, false, Set.of(Permission.ENTER));
        assertThrows(ArchiveException.class, () -> Archive.restore(owner, allocation, "nullspace:classic", true, false, ArchiveSettings.DEFAULT, rooms, Map.of(owner, self), Map.of(), Map.of()));
        assertThrows(ArchiveException.class, () -> Archive.restore(owner, allocation, "not a preset", true, false, ArchiveSettings.DEFAULT, rooms, Map.of(), Map.of(), Map.of()));
        Map<String, Set<Permission>> manyPresets = new HashMap<>();
        for (int i = 0; i < 33; i++) manyPresets.put("p" + i, Set.of(Permission.ENTER));
        assertThrows(ArchiveException.class, () -> Archive.restore(owner, allocation, "nullspace:classic", true, false, ArchiveSettings.DEFAULT, rooms, Map.of(), Map.of(), manyPresets));
        assertThrows(ArchiveException.class, () -> new Invitation(Invitation.Kind.PERMANENT, 0, false, EnumSet.of(Permission.ADMIN)));
        assertThrows(ArchiveException.class, () -> new Invitation(Invitation.Kind.TIMED, -1, false, Set.of(Permission.ENTER)));
        assertThrows(ArchiveException.class, () -> new Visit(HOME, -1, Set.of()));
    }

    @Test void invitationTimeArithmeticCannotOverflowOrGoNegative() {
        Archive a = archive();
        Set<Permission> enter = Set.of(Permission.ENTER);
        for (long bad : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE, POLICY.maxInvitationDurationMillis() + 1})
            assertThrows(ArchiveException.class, () -> AccessControl.invite(a, owner, guest, Invitation.Kind.TIMED, bad, enter, 1_000, POLICY), "duration " + bad);
        assertThrows(ArchiveException.class, () -> AccessControl.invite(a, owner, guest, Invitation.Kind.TIMED, 1, enter, -5, POLICY), "negative clock");
        // a clock at the very end of the long range is refused (ArithmeticException is not an unchecked leak: it is a RuntimeException the boundary reports).
        assertThrows(RuntimeException.class, () -> AccessControl.invite(a, owner, guest, Invitation.Kind.TIMED, 1000, enter, Long.MAX_VALUE - 10, POLICY));
        assertTrue(a.invitations().isEmpty(), "a refused invitation leaves nothing behind");
        AccessControl.invite(a, owner, guest, Invitation.Kind.TIMED, POLICY.maxInvitationDurationMillis(), enter, 1_000, POLICY);
        assertTrue(AccessControl.mayEnter(a, guest, 1_000 + POLICY.maxInvitationDurationMillis() - 1, POLICY));
        assertFalse(AccessControl.mayEnter(a, guest, 1_000 + POLICY.maxInvitationDurationMillis(), POLICY));
    }

    @Test void guestLimitCountsEveryInvitationAndReinvitingDoesNotGrowIt() {
        Archive a = archive();
        ServerPolicy two = new ServerPolicy(false, false, true, true, 16, 256, 9, 2, POLICY.maxInvitationDurationMillis(), POLICY.allowedGuestPermissions());
        Set<Permission> enter = Set.of(Permission.ENTER);
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, enter, 0, two);
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, enter, 0, two);
        AccessControl.invite(a, owner, stranger, Invitation.Kind.PERMANENT, 0, enter, 0, two);
        assertThrows(ArchiveException.class, () -> AccessControl.invite(a, owner, UUID.randomUUID(), Invitation.Kind.PERMANENT, 0, enter, 0, two));
        assertEquals(2, a.invitations().size());
    }

    @Test void returnLocationsRejectNonsense() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 3e7})
            assertThrows(ArchiveException.class, () -> new ReturnLocation("minecraft:overworld", bad, 70, 0, 0, 0), "x=" + bad);
        assertThrows(ArchiveException.class, () -> new ReturnLocation("minecraft:overworld", 0, Double.NaN, 0, 0, 0));
        assertThrows(ArchiveException.class, () -> new ReturnLocation("Not A Dimension", 0, 70, 0, 0, 0));
        assertThrows(ArchiveException.class, () -> new ReturnLocation("minecraft:overworld", 0, 70, 0, Float.NaN, 0));
    }

    @Test void presetNamesAreAsciiAndBounded() {
        Archive a = archive();
        for (String bad : new String[] {"", " ".repeat(49), "x".repeat(49), "na\nme", "naéme", "\u202eevil", "a/b", "<b>x</b>", "x\u0000"})
            assertThrows(ArchiveException.class, () -> a.savePreset(owner, bad, Set.of(Permission.ENTER)), "name " + bad.length() + " chars");
        a.savePreset(owner, "Co-op builders", Set.of(Permission.ENTER, Permission.BUILD));
        assertEquals(1, a.presets().size());
    }

    @Test void aGuestWhoAlreadyVisitsKeepsTheOriginalReturnPoint() {
        Archive a = archive();
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, POLICY);
        AccessControl.enter(a, guest, HOME, 1, POLICY);
        ReturnLocation elsewhere = new ReturnLocation("minecraft:the_nether", 5, 70, 5, 0, 0);
        assertEquals(HOME, AccessControl.enter(a, guest, elsewhere, 2, POLICY).returnLocation());
    }

    @Test void policyNarrowingRevokesLiveRightsImmediately() {
        Archive a = archive();
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, EnumSet.of(Permission.ENTER, Permission.BUILD), 0, POLICY);
        AccessControl.enter(a, guest, HOME, 1, POLICY);
        assertTrue(AccessControl.can(a, guest, Permission.BUILD, 0, POLICY));
        ServerPolicy noBuild = new ServerPolicy(false, false, true, true, 16, 256, 9, 32, POLICY.maxInvitationDurationMillis(), Set.of(Permission.ENTER));
        assertFalse(AccessControl.can(a, guest, Permission.BUILD, 0, noBuild));
        ServerPolicy noSpawn = new ServerPolicy(false, false, false, true, 16, 256, 9, 32, POLICY.maxInvitationDurationMillis(), POLICY.allowedGuestPermissions());
        assertFalse(AccessControl.can(a, owner, Permission.SET_SPAWN, 0, noSpawn), "the global spawn veto applies to the owner too");
    }
}
