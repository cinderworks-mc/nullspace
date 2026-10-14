package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ArchiveCircleTest {
    private static final long HOUR = 3_600_000L, NOW = 10 * HOUR;
    private final ArchiveRegistry registry = new ArchiveRegistry();
    private final Map<UUID, Archive> byOwner = new HashMap<>();
    private final Function<UUID, Archive> find = byOwner::get;
    private final ServerPolicy policy = policy(Set.of(Permission.ENTER, Permission.BUILD, Permission.STORAGE));

    private static ServerPolicy policy(Set<Permission> allowed) {
        return new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 720 * HOUR, allowed);
    }

    private Archive archive(boolean initialized) {
        Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic");
        if (initialized) a.markInitialized();
        byOwner.put(a.owner(), a);
        return a;
    }

    private void invite(Archive host, UUID guest, Invitation.Kind kind, long duration) {
        AccessControl.invite(host, host.owner(), guest, kind, duration, Set.of(Permission.ENTER), 0, policy);
    }

    private boolean linked(UUID a, UUID b, long now) { return ArchiveCircle.linked(find, a, b, now, policy); }

    @Test void invitationLinksBothDirections() {
        Archive a = archive(true); UUID b = UUID.randomUUID();
        invite(a, b, Invitation.Kind.PERMANENT, 0);
        assertTrue(linked(a.owner(), b, NOW), "host to guest");
        assertTrue(linked(b, a.owner(), NOW), "guest to host (Tempad's Screening Device passes them swapped)");
    }

    @Test void invitedBothWaysAndOneWayAreAllLinked() {
        Archive a = archive(true), b = archive(true);
        invite(a, b.owner(), Invitation.Kind.PERMANENT, 0);
        assertTrue(linked(a.owner(), b.owner(), NOW) && linked(b.owner(), a.owner(), NOW));
        invite(b, a.owner(), Invitation.Kind.PERMANENT, 0);
        assertTrue(linked(a.owner(), b.owner(), NOW) && linked(b.owner(), a.owner(), NOW));
    }

    @Test void noInvitationMeansNoLinkAndOtherInvitationsDoNotLeak() {
        Archive a = archive(true), c = archive(true); UUID b = UUID.randomUUID(), stranger = UUID.randomUUID();
        invite(a, b, Invitation.Kind.PERMANENT, 0);
        assertFalse(linked(a.owner(), stranger, NOW));
        assertFalse(linked(stranger, a.owner(), NOW));
        assertFalse(linked(b, stranger, NOW), "two guests of the same host are not linked to each other");
        assertFalse(linked(b, c.owner(), NOW));
        assertFalse(linked(a.owner(), c.owner(), NOW));
    }

    @Test void expiredTimedInvitationStopsCountingInBothDirections() {
        Archive a = archive(true); UUID b = UUID.randomUUID();
        invite(a, b, Invitation.Kind.TIMED, HOUR);
        assertTrue(linked(a.owner(), b, HOUR - 1));
        assertFalse(linked(a.owner(), b, HOUR), "expires exactly at the deadline, like entry");
        assertFalse(linked(b, a.owner(), HOUR + 5 * HOUR));
    }

    @Test void usedSingleVisitStopsCounting() {
        Archive a = archive(true); UUID b = UUID.randomUUID();
        invite(a, b, Invitation.Kind.SINGLE_VISIT, 0);
        assertTrue(linked(a.owner(), b, NOW), "unused single visit still counts");
        AccessControl.enter(a, b, new ReturnLocation("minecraft:overworld", 0, 70, 0, 0, 0), NOW, policy);
        assertFalse(linked(a.owner(), b, NOW));
        assertFalse(linked(b, a.owner(), NOW));
    }

    @Test void revokedInvitationStopsCounting() {
        Archive a = archive(true); UUID b = UUID.randomUUID();
        invite(a, b, Invitation.Kind.PERMANENT, 0);
        AccessControl.remove(a, a.owner(), b);
        assertFalse(linked(a.owner(), b, NOW));
        assertFalse(linked(b, a.owner(), NOW));
    }

    @Test void aPlayerIsNeverLinkedToThemselves() {
        Archive a = archive(true);
        assertFalse(linked(a.owner(), a.owner(), NOW));
    }

    @Test void unknownMissingAndUnclaimedIdsAreNo() {
        Archive a = archive(true);
        assertFalse(linked(UUID.randomUUID(), UUID.randomUUID(), NOW));
        assertFalse(linked(a.owner(), UUID.randomUUID(), NOW));
        assertFalse(linked(null, UUID.randomUUID(), NOW));
        assertFalse(linked(a.owner(), null, NOW));
        assertFalse(ArchiveCircle.linked(null, a.owner(), UUID.randomUUID(), NOW, policy));
        assertFalse(ArchiveCircle.linked(find, a.owner(), UUID.randomUUID(), NOW, null));
    }

    @Test void uninitialisedArchiveDoesNotLink() {
        Archive a = archive(false); UUID b = UUID.randomUUID();
        invite(a, b, Invitation.Kind.PERMANENT, 0);
        assertFalse(linked(a.owner(), b, NOW));
    }

    @Test void policyWithoutEnterLinksNobody() {
        Archive a = archive(true); UUID b = UUID.randomUUID();
        invite(a, b, Invitation.Kind.PERMANENT, 0);
        assertFalse(ArchiveCircle.linked(find, a.owner(), b, NOW, policy(Set.of(Permission.BUILD))), "guest visits switched off");
    }

    @Test void agreesWithEntryRefusalForEveryInvitationState() {
        Archive a = archive(true); UUID b = UUID.randomUUID();
        for (Invitation.Kind kind : Invitation.Kind.values()) {
            invite(a, b, kind, HOUR);
            for (long now : new long[] {NOW / 100, HOUR, 5 * HOUR}) {
                assertEquals(AccessControl.entryRefusal(a, b, now, policy) == null, linked(a.owner(), b, now), kind + " at " + now);
            }
        }
        AccessControl.remove(a, a.owner(), b);
        assertEquals(AccessControl.entryRefusal(a, b, NOW, policy) == null, linked(a.owner(), b, NOW));
    }

    private final Map<UUID, ArchiveCircle.Standing> standingOf = new HashMap<>();
    private boolean visible(UUID viewer, UUID holder, long now) {
        return ArchiveCircle.visible(find, id -> standingOf.getOrDefault(id, ArchiveCircle.Standing.OUTSIDE), viewer, holder, now, policy);
    }

    @Test void aHolderOutsideAnyArchiveIsVisibleToTheLinkedAndOnlyTheLinked() {
        Archive a = archive(true); UUID guest = UUID.randomUUID(), stranger = UUID.randomUUID();
        invite(a, guest, Invitation.Kind.PERMANENT, 0);
        assertTrue(visible(guest, a.owner(), NOW));
        assertFalse(visible(stranger, a.owner(), NOW), "not linked is still hidden");
    }

    @Test void ownerInsideOwnArchiveIsHiddenFromALinkedPlayerWhoMayNotEnterButShownToInvitedGuests() {
        Archive guestHost = archive(true), a = archive(true); UUID guest = UUID.randomUUID();
        invite(a, guest, Invitation.Kind.PERMANENT, 0);
        invite(guestHost, a.owner(), Invitation.Kind.PERMANENT, 0); // a's owner is invited to guestHost's archive: guestHost is linked to a's owner, not invited to a's archive
        standingOf.put(a.owner(), ArchiveCircle.Standing.inside(a));
        assertTrue(linked(guestHost.owner(), a.owner(), NOW), "linked by the reverse invitation");
        assertFalse(visible(guestHost.owner(), a.owner(), NOW), "but may not enter, so the coordinate is hidden");
        assertTrue(visible(guest, a.owner(), NOW), "the invited guest still sees the owner");
        standingOf.remove(a.owner());
        assertTrue(visible(guestHost.owner(), a.owner(), NOW), "the owner left the archive: visible again");
    }

    @Test void holderInsideAnotherPersonsArchiveFollowsThatArchivesEntryRules() {
        Archive home = archive(true), other = archive(true); UUID viewer = UUID.randomUUID();
        invite(home, viewer, Invitation.Kind.PERMANENT, 0);   // viewer linked to home's owner
        invite(other, home.owner(), Invitation.Kind.PERMANENT, 0); // the holder visits other's archive
        standingOf.put(home.owner(), ArchiveCircle.Standing.inside(other));
        assertFalse(visible(viewer, home.owner(), NOW), "viewer may not enter the archive the holder visits");
        invite(other, viewer, Invitation.Kind.TIMED, HOUR);
        assertTrue(visible(viewer, home.owner(), HOUR - 1));
        assertFalse(visible(viewer, home.owner(), HOUR), "the other archive's invitation expired: hidden again");
        assertTrue(visible(other.owner(), home.owner(), NOW), "the archive's own owner may always see a visitor");
    }

    @Test void singleVisitUsedElsewhereHidesButAgreesWithEntryRefusal() {
        Archive a = archive(true), b = archive(true);
        invite(b, a.owner(), Invitation.Kind.PERMANENT, 0);
        invite(a, b.owner(), Invitation.Kind.SINGLE_VISIT, 0);
        standingOf.put(a.owner(), ArchiveCircle.Standing.inside(a));
        assertTrue(visible(b.owner(), a.owner(), NOW), "unused single visit may enter");
        AccessControl.enter(a, b.owner(), new ReturnLocation("minecraft:overworld", 0, 70, 0, 0, 0), NOW, policy);
        assertFalse(visible(b.owner(), a.owner(), NOW), "used: linked via the other archive, but entry refused, so hidden");
    }

    @Test void failsClosedOnUnknownSpotsAndMissingLookups() {
        Archive a = archive(true); UUID guest = UUID.randomUUID();
        invite(a, guest, Invitation.Kind.PERMANENT, 0);
        standingOf.put(a.owner(), ArchiveCircle.Standing.UNKNOWN);
        assertFalse(visible(guest, a.owner(), NOW), "in the archive dimension on no allocation");
        assertFalse(ArchiveCircle.visible(find, null, guest, a.owner(), NOW, policy), "no position lookup");
        assertFalse(ArchiveCircle.visible(find, id -> null, guest, a.owner(), NOW, policy), "lookup answers null");
        assertEquals(ArchiveCircle.Standing.UNKNOWN, ArchiveCircle.Standing.inside(null));
    }
}
