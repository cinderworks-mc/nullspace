package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// timed access ends when the time is up: invitations and cards, rights, staying, entry and the sweep lists, plus
// everything that must not change
class TimedAccessEndTest {
    private static final ReturnLocation HOME = new ReturnLocation("minecraft:overworld", 10, 70, -5, 90, 0);
    private static final Set<Permission> ENTER = Set.of(Permission.ENTER);
    private static final long HOUR = 3_600_000L;
    private static final long T0 = 1_000_000L;
    private static final long END = T0 + HOUR;

    private final UUID owner = UUID.randomUUID(), alex = UUID.randomUUID(), blake = UUID.randomUUID();
    private final ArchiveRegistry registry = new ArchiveRegistry();
    private final Archive archive = registry.claim(owner, "nullspace:classic");
    private final ServerPolicy policy = ServerPolicy.DEFAULT;

    private static Set<Permission> everything() {
        Set<Permission> all = EnumSet.allOf(Permission.class);
        all.remove(Permission.ADMIN);
        return all;
    }
    private void invite(Archive a, UUID guest, Invitation.Kind kind, long duration, Set<Permission> rights) {
        AccessControl.invite(a, a.owner(), guest, kind, duration, rights, T0, policy);
    }
    private CardGrant card(CardGrant.Kind kind, long duration) {
        return AccessControl.printCard(archive, owner, UUID.randomUUID(), kind, duration, ENTER, false, T0, policy);
    }
    private Visit enterWithCard(UUID player, CardGrant card, long now) {
        return AccessControl.enter(archive, player, "Name", HOME, now, policy, card.token());
    }

    @Test void everyRightOfAnInvitedGuestEndsAtTheExactMomentTheInvitationExpires() {
        invite(archive, alex, Invitation.Kind.TIMED, HOUR, everything());
        AccessControl.enter(archive, alex, HOME, T0 + 1, policy);
        for (Permission p : Permission.values()) {
            if (p == Permission.ADMIN || !policy.allowedGuestPermissions().contains(p)) continue;
            assertTrue(AccessControl.can(archive, alex, p, END - 1, policy), p + " one millisecond before");
            assertFalse(AccessControl.can(archive, alex, p, END, policy), p + " at the boundary");
            assertFalse(AccessControl.can(archive, alex, p, END + 5 * HOUR, policy), p + " long after");
        }
        assertTrue(AccessControl.mayStayAt(archive, alex, END - 1, policy));
        assertFalse(AccessControl.mayStayAt(archive, alex, END, policy));
        assertTrue(AccessControl.mayEnter(archive, alex, END - 1, policy));
        assertFalse(AccessControl.mayEnter(archive, alex, END, policy));
        assertEquals(List.of(), AccessControl.expiredVisitors(archive, END - 1, policy));
        assertEquals(List.of(alex), AccessControl.expiredVisitors(archive, END, policy));
    }

    @Test void anExpiredGuestCannotComeBackInAndOwnersAreNeverAffected() {
        invite(archive, alex, Invitation.Kind.TIMED, HOUR, ENTER);
        assertEquals(AccessControl.EntryRefusal.EXPIRED, AccessControl.entryRefusal(archive, alex, END, policy));
        assertThrows(ArchiveSecurityException.class, () -> AccessControl.enter(archive, alex, HOME, END, policy));
        long far = END + 1000 * HOUR;
        AccessControl.enter(archive, owner, HOME, T0, policy);
        assertTrue(AccessControl.mayStayAt(archive, owner, far, policy));
        assertTrue(AccessControl.mayEnter(archive, owner, far, policy));
        for (Permission p : Permission.values()) assertTrue(AccessControl.can(archive, owner, p, far, policy), p.name());
        assertEquals(List.of(), AccessControl.expiredVisitors(archive, far, policy));
    }

    @Test void permanentAndSingleVisitInvitationsNeverTimeOut() {
        Set<Permission> rights = Set.of(Permission.ENTER, Permission.BUILD);
        invite(archive, alex, Invitation.Kind.PERMANENT, 0, rights);
        invite(archive, blake, Invitation.Kind.SINGLE_VISIT, 0, rights);
        AccessControl.enter(archive, alex, HOME, T0, policy);
        AccessControl.enter(archive, blake, HOME, T0, policy);
        long far = T0 + 5000 * HOUR;
        for (UUID guest : List.of(alex, blake)) {
            assertTrue(AccessControl.can(archive, guest, Permission.BUILD, far, policy));
            assertTrue(AccessControl.mayStayAt(archive, guest, far, policy));
        }
        assertEquals(List.of(), AccessControl.expiredVisitors(archive, far, policy));
        assertEquals(List.of(), AccessControl.dropExpiredInvitations(archive, far));
        assertEquals(Set.of(alex, blake), archive.invitations().keySet());
        assertTrue(AccessControl.mayEnter(archive, alex, far, policy));
    }

    @Test void theInvitationSweepRemovesOnlyExpiredOnesAndOnlyFromTheBoundary() {
        invite(archive, alex, Invitation.Kind.TIMED, HOUR, ENTER);
        invite(archive, blake, Invitation.Kind.TIMED, 2 * HOUR, ENTER);
        assertEquals(List.of(), AccessControl.dropExpiredInvitations(archive, END - 1));
        assertEquals(List.of(alex), AccessControl.dropExpiredInvitations(archive, END));
        assertEquals(Set.of(blake), archive.invitations().keySet());
    }

    @Test void aGuestWhoLoggedOutInsideIsListedAsExpiredWithTheirReturnPointKept() {
        invite(archive, alex, Invitation.Kind.TIMED, HOUR, ENTER);
        AccessControl.enter(archive, alex, HOME, T0, policy);
        long afterRestart = END + 100 * HOUR;
        assertEquals(List.of(alex), AccessControl.expiredVisitors(archive, afterRestart, policy));
        AccessControl.dropExpiredInvitations(archive, afterRestart);
        assertEquals(HOME, AccessControl.exit(archive, alex).orElseThrow());
        assertFalse(archive.activeVisits().containsKey(alex));
        assertFalse(archive.invitations().containsKey(alex));
    }

    @Test void aTimedCardEndsTheVisitItStartedAtItsBoundary() {
        CardGrant card = card(CardGrant.Kind.TIMED, HOUR);
        enterWithCard(alex, card, T0 + 1);
        assertTrue(AccessControl.can(archive, alex, Permission.ENTER, END - 1, policy));
        assertTrue(AccessControl.mayStayAt(archive, alex, END - 1, policy));
        assertFalse(AccessControl.can(archive, alex, Permission.ENTER, END, policy));
        assertFalse(AccessControl.mayStayAt(archive, alex, END, policy));
        assertEquals(List.of(alex), AccessControl.expiredVisitors(archive, END, policy));
        assertEquals(AccessControl.EntryRefusal.CARD_EXPIRED, AccessControl.entryRefusal(archive, alex, END, policy, card.token()));
        assertNotNull(archive.cards().get(card.token()), "the record stays, the terminal shows it as expired");
    }

    @Test void singleVisitAndUntilRevokedCardsKeepTheirVisitsForever() {
        CardGrant once = card(CardGrant.Kind.SINGLE_VISIT, 0), open = card(CardGrant.Kind.UNTIL_REVOKED, 0);
        enterWithCard(alex, once, T0);
        enterWithCard(blake, open, T0);
        long far = T0 + 5000 * HOUR;
        for (UUID guest : List.of(alex, blake)) {
            assertTrue(AccessControl.can(archive, guest, Permission.ENTER, far, policy));
            assertTrue(AccessControl.mayStayAt(archive, guest, far, policy));
        }
        assertEquals(List.of(), AccessControl.expiredVisitors(archive, far, policy));
    }

    @Test void aRevokedCardStillEndsItsVisitAsBefore() {
        CardGrant card = card(CardGrant.Kind.UNTIL_REVOKED, 0);
        enterWithCard(alex, card, T0);
        AccessControl.revokeCard(archive, owner, card.number(), T0 + 1);
        assertEquals(List.of(alex), AccessControl.cardVisitorsToEvict(archive, policy));
        assertEquals(List.of(), AccessControl.expiredVisitors(archive, T0 + 2, policy));
        assertFalse(AccessControl.can(archive, alex, Permission.ENTER, T0 + 2, policy));
    }

    @Test void anExpiredInvitationFallsThroughToAValidCardForVisitsStartedOnTheCard() {
        invite(archive, alex, Invitation.Kind.TIMED, HOUR, Set.of(Permission.ENTER, Permission.BUILD));
        CardGrant card = card(CardGrant.Kind.UNTIL_REVOKED, 0);
        Visit visit = enterWithCard(alex, card, END + 1);
        assertEquals(card.token(), visit.cardToken(), "the dead invitation does not decide");
        assertTrue(AccessControl.can(archive, alex, Permission.ENTER, END + 2, policy));
        assertFalse(AccessControl.can(archive, alex, Permission.BUILD, END + 2, policy), "the card gives enter only, the invitation's build is gone");
        assertTrue(AccessControl.mayStayAt(archive, alex, END + 2, policy));
        assertEquals(List.of(), AccessControl.expiredVisitors(archive, END + 2, policy));
        assertEquals(List.of(alex), AccessControl.dropExpiredInvitations(archive, END + 2));
        assertTrue(AccessControl.can(archive, alex, Permission.ENTER, END + 3, policy), "the card visit goes on once the invitation is swept");
    }

    @Test void aGuestEvictedOnAnExpiredInvitationCanStillUseAValidCard() {
        invite(archive, alex, Invitation.Kind.TIMED, HOUR, ENTER);
        CardGrant card = card(CardGrant.Kind.UNTIL_REVOKED, 0);
        AccessControl.enter(archive, alex, HOME, T0, policy);
        assertEquals(List.of(alex), AccessControl.expiredVisitors(archive, END, policy), "the visit rested on the invitation");
        AccessControl.dropExpiredInvitations(archive, END);
        AccessControl.exit(archive, alex);
        assertNull(AccessControl.entryRefusal(archive, alex, END, policy, card.token()));
        assertEquals(card.token(), enterWithCard(alex, card, END).cardToken());
    }

    @Test void anExpiredInvitationOnATimedCardVisitEndsWhenBothAreDead() {
        invite(archive, alex, Invitation.Kind.TIMED, HOUR, ENTER);
        CardGrant card = card(CardGrant.Kind.TIMED, 3 * HOUR);
        enterWithCard(alex, card, END);
        assertEquals(List.of(), AccessControl.expiredVisitors(archive, END + 1, policy));
        assertEquals(List.of(alex), AccessControl.expiredVisitors(archive, T0 + 3 * HOUR, policy));
        assertFalse(AccessControl.can(archive, alex, Permission.ENTER, T0 + 3 * HOUR, policy));
    }

    @Test void aTeammateWithAnExpiredInvitationFallsBackToTheTeamGrant() {
        var teams = new TeamAccessTest.FakeTeams();
        teams.join("p1", owner, TeamRelation.OWNER);
        teams.join("p1", alex, TeamRelation.MEMBER);
        ServerPolicy members = policy.withTeams(new TeamPolicy(TeamPolicy.Mode.MEMBERS, TeamRelation.MEMBER, 16, teams));
        AccessControl.enableTeamAccess(archive, owner, members);
        AccessControl.invite(archive, owner, alex, Invitation.Kind.TIMED, HOUR, Set.of(Permission.ENTER, Permission.BUILD), T0, members);
        AccessControl.enter(archive, alex, HOME, T0, members);
        assertTrue(AccessControl.can(archive, alex, Permission.BUILD, END - 1, members));
        assertFalse(AccessControl.can(archive, alex, Permission.BUILD, END, members), "the team grant is enter only");
        assertTrue(AccessControl.can(archive, alex, Permission.ENTER, END, members));
        assertTrue(AccessControl.mayEnter(archive, alex, END, members));
        assertTrue(AccessControl.mayStayAt(archive, alex, END, members));
        assertEquals(List.of(), AccessControl.expiredVisitors(archive, END, members));
        AccessControl.dropExpiredInvitations(archive, END);
        assertTrue(AccessControl.can(archive, alex, Permission.ENTER, END + 1, members));
        teams.leave(alex);
        assertEquals(List.of(alex), AccessControl.teamVisitorsToEvict(archive, members), "the team sweep owns it from here");
    }

    @Test void aTeammateGoneFromTheTeamLosesAnExpiredInvitationVisitToo() {
        var teams = new TeamAccessTest.FakeTeams();
        teams.join("p1", owner, TeamRelation.OWNER);
        teams.join("p1", alex, TeamRelation.MEMBER);
        ServerPolicy members = policy.withTeams(new TeamPolicy(TeamPolicy.Mode.MEMBERS, TeamRelation.MEMBER, 16, teams));
        AccessControl.enableTeamAccess(archive, owner, members);
        AccessControl.invite(archive, owner, alex, Invitation.Kind.TIMED, HOUR, ENTER, T0, members);
        AccessControl.enter(archive, alex, HOME, T0, members);
        teams.leave(alex);
        assertEquals(List.of(alex), AccessControl.expiredVisitors(archive, END, members));
    }

    @Test void aGuestExpiringInOneArchiveKeepsTheOtherOne() {
        Archive other = registry.claim(UUID.randomUUID(), "nullspace:classic");
        invite(archive, alex, Invitation.Kind.TIMED, HOUR, ENTER);
        invite(other, alex, Invitation.Kind.PERMANENT, 0, ENTER);
        AccessControl.enter(archive, alex, HOME, T0, policy);
        assertEquals(List.of(alex), AccessControl.expiredVisitors(archive, END, policy));
        assertEquals(List.of(), AccessControl.expiredVisitors(other, END, policy));
        assertEquals(List.of(), AccessControl.dropExpiredInvitations(other, END));
        assertTrue(AccessControl.mayEnter(other, alex, END, policy));
    }
}
