package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArchiveCardTest {
    private static final ReturnLocation HOME = new ReturnLocation("minecraft:overworld", 10, 70, -5, 90, 0);
    private static final Set<Permission> ENTER = Set.of(Permission.ENTER);
    private static final long HOUR = 3_600_000L;
    private static final long T0 = 1_000_000L;

    private final UUID owner = UUID.randomUUID(), alex = UUID.randomUUID(), blake = UUID.randomUUID(), stranger = UUID.randomUUID();
    private final ArchiveRegistry registry = new ArchiveRegistry();
    private final Archive archive = registry.claim(owner, "nullspace:classic");
    private final ServerPolicy policy = ServerPolicy.DEFAULT;

    private CardGrant print(CardGrant.Kind kind, long duration, Set<Permission> perms, boolean holder) {
        return AccessControl.printCard(archive, owner, UUID.randomUUID(), kind, duration, perms, holder, T0, policy);
    }
    private CardGrant single() { return print(CardGrant.Kind.SINGLE_VISIT, 0, ENTER, false); }
    private CardGrant timed(long duration) { return print(CardGrant.Kind.TIMED, duration, ENTER, false); }
    private CardGrant forever() { return print(CardGrant.Kind.UNTIL_REVOKED, 0, ENTER, false); }
    private AccessControl.EntryRefusal refusal(UUID player, CardGrant card, long now) { return AccessControl.entryRefusal(archive, player, now, policy, card.token()); }
    private Visit enter(UUID player, CardGrant card, long now) { return AccessControl.enter(archive, player, "Name", HOME, now, policy, card.token()); }
    private CardGrant fresh(CardGrant card) { return archive.cards().get(card.token()); }

    @Test void aCardThatWasNeverPrintedGivesNothing() {
        assertEquals(AccessControl.EntryRefusal.NOT_INVITED, AccessControl.entryRefusal(archive, alex, T0, policy, UUID.randomUUID()), "a guessed token is judged as no card at all");
        assertEquals(AccessControl.EntryRefusal.NOT_INVITED, AccessControl.entryRefusal(archive, alex, T0, policy, null));
    }

    @Test void aPrintedCardLetsAStrangerInWithEnterOnly() {
        CardGrant card = forever();
        assertNull(refusal(alex, card, T0));
        assertFalse(AccessControl.mayEnter(archive, alex, T0, policy), "the card is not an invitation: the listing and plain checks do not see it");
        Visit visit = enter(alex, card, T0);
        assertEquals(ENTER, visit.permissions());
        assertEquals(card.token(), visit.cardToken());
        assertTrue(archive.invitations().isEmpty(), "using a card never stores an invitation");
        assertFalse(AccessControl.can(archive, alex, Permission.BUILD, T0, policy), "ENTER only: building is refused");
        assertFalse(AccessControl.can(archive, alex, Permission.STORAGE, T0, policy));
    }

    @Test void theFirstUserBindsTheCardAndNobodyElseCanUseIt() {
        CardGrant card = forever();
        assertNull(refusal(blake, card, T0), "unbound until first used");
        enter(alex, card, T0 + 1);
        CardGrant bound = fresh(card);
        assertEquals(alex, bound.boundTo());
        assertEquals("Name", bound.boundName());
        assertEquals(1, bound.uses());
        assertEquals(AccessControl.EntryRefusal.CARD_BOUND, refusal(blake, card, T0 + 2));
        assertNull(refusal(alex, card, T0 + 2), "the bound player can come back");
        assertThrows(ArchiveSecurityException.class, () -> enter(blake, card, T0 + 3));
        assertFalse(archive.activeVisits().containsKey(blake));
    }

    @Test void aHolderCardIsNeverBound() {
        CardGrant card = print(CardGrant.Kind.UNTIL_REVOKED, 0, ENTER, true);
        enter(alex, card, T0 + 1);
        assertNull(fresh(card).boundTo());
        assertNull(refusal(blake, card, T0 + 2), "whoever holds it can use it");
        enter(blake, card, T0 + 2);
        assertEquals(2, fresh(card).uses());
    }

    @Test void aSingleVisitCardIsSpentByTheEntryAndDiesWhenTheVisitEnds() {
        CardGrant card = single();
        enter(alex, card, T0 + 1);
        assertEquals(CardGrant.State.USED, fresh(card).state(T0 + 1));
        assertTrue(AccessControl.can(archive, alex, Permission.ENTER, T0, policy) || archive.activeVisits().containsKey(alex), "the visit under way goes on");
        assertSame(archive.activeVisits().get(alex), enter(alex, card, T0 + 2), "a repeat crossing inside the visit changes nothing");
        AccessControl.exit(archive, alex);
        assertEquals(AccessControl.EntryRefusal.CARD_USED, refusal(alex, card, T0 + 3), "after the visit the card is dead, for its own user as well");
        assertThrows(ArchiveSecurityException.class, () -> enter(alex, card, T0 + 3));
        assertEquals(AccessControl.EntryRefusal.CARD_USED, refusal(blake, card, T0 + 3), "spent is spent, whoever asks (a bound card says so first only while it works)");
    }

    @Test void aTimedCardStopsAtItsExpiryButAVisitUnderWayIsNotCutShort() {
        CardGrant card = timed(HOUR);
        assertNull(refusal(alex, card, T0 + HOUR - 1));
        enter(alex, card, T0 + HOUR - 1);
        assertEquals(AccessControl.EntryRefusal.CARD_EXPIRED, refusal(alex, card, T0 + HOUR));
        assertEquals(AccessControl.EntryRefusal.CARD_EXPIRED, refusal(blake, card, T0 + HOUR + 5));
        assertTrue(archive.activeVisits().containsKey(alex), "the visitor is still in");
        assertTrue(AccessControl.cardVisitorsToEvict(archive, policy).isEmpty(), "like an expired invitation: expiry does not evict");
        AccessControl.exit(archive, alex);
        assertThrows(ArchiveSecurityException.class, () -> enter(alex, card, T0 + HOUR + 10));
    }

    @Test void aTimedCardWithinTheServerLimitOnly() {
        assertThrows(ArchiveException.class, () -> timed(0));
        assertThrows(ArchiveException.class, () -> timed(policy.maxInvitationDurationMillis() + 1));
        assertNotNull(timed(policy.maxInvitationDurationMillis()));
    }

    @Test void revokingKillsTheCardAtOnceAndEvictsWhoIsInside() {
        CardGrant card = forever();
        enter(alex, card, T0 + 1);
        assertTrue(AccessControl.can(archive, alex, Permission.ENTER, T0, policy));
        AccessControl.revokeCard(archive, owner, card.number(), T0 + 2);
        assertEquals(AccessControl.EntryRefusal.CARD_REVOKED, refusal(alex, card, T0 + 3));
        assertEquals(AccessControl.EntryRefusal.CARD_REVOKED, refusal(blake, card, T0 + 3));
        assertEquals(List.of(alex), AccessControl.cardVisitorsToEvict(archive, policy));
        assertFalse(AccessControl.can(archive, alex, Permission.ENTER, T0, policy), "rights go with the card, before the eviction even runs");
        assertThrows(ArchiveException.class, () -> AccessControl.revokeCard(archive, owner, card.number(), T0 + 4), "a revoked card cannot be revoked again");
    }

    @Test void onlyTheOwnerPrintsRevokesAndForgets() {
        assertThrows(ArchiveSecurityException.class, () -> AccessControl.printCard(archive, alex, UUID.randomUUID(), CardGrant.Kind.UNTIL_REVOKED, 0, ENTER, false, T0, policy));
        CardGrant card = forever();
        assertThrows(ArchiveSecurityException.class, () -> AccessControl.revokeCard(archive, alex, card.number(), T0));
        AccessControl.revokeCard(archive, owner, card.number(), T0);
        assertThrows(ArchiveSecurityException.class, () -> AccessControl.forgetCard(archive, alex, card.number(), T0));
    }

    @Test void forgettingOnlyWorksOnCardsThatNoLongerWork() {
        CardGrant live = forever(), dead = forever();
        AccessControl.revokeCard(archive, owner, dead.number(), T0);
        assertThrows(ArchiveException.class, () -> AccessControl.forgetCard(archive, owner, live.number(), T0));
        AccessControl.forgetCard(archive, owner, dead.number(), T0);
        assertFalse(archive.cards().containsKey(dead.token()));
        assertThrows(ArchiveException.class, () -> AccessControl.revokeCard(archive, owner, dead.number(), T0), "gone");
        assertEquals(AccessControl.EntryRefusal.NOT_INVITED, refusal(alex, dead, T0), "a forgotten card is just an unknown token");
    }

    @Test void capCountsOnlyLiveCards() {
        ServerPolicy two = policy.withCards(new CardPolicy(true, 2));
        AccessControl.printCard(archive, owner, UUID.randomUUID(), CardGrant.Kind.UNTIL_REVOKED, 0, ENTER, false, T0, two);
        CardGrant second = AccessControl.printCard(archive, owner, UUID.randomUUID(), CardGrant.Kind.UNTIL_REVOKED, 0, ENTER, false, T0, two);
        var thrown = assertThrows(ArchiveException.class, () -> AccessControl.printCard(archive, owner, UUID.randomUUID(), CardGrant.Kind.UNTIL_REVOKED, 0, ENTER, false, T0, two));
        assertEquals("error.nullspace.card.limit", thrown.key());
        AccessControl.revokeCard(archive, owner, second.number(), T0);
        assertNotNull(AccessControl.printCard(archive, owner, UUID.randomUUID(), CardGrant.Kind.UNTIL_REVOKED, 0, ENTER, false, T0, two), "a revoked card frees a place");
        assertEquals(2, AccessControl.liveCards(archive, T0));
    }

    @Test void anExpiredOrSpentCardFreesItsPlace() {
        ServerPolicy one = policy.withCards(new CardPolicy(true, 1));
        AccessControl.printCard(archive, owner, UUID.randomUUID(), CardGrant.Kind.TIMED, HOUR, ENTER, false, T0, one);
        assertThrows(ArchiveException.class, () -> AccessControl.printCard(archive, owner, UUID.randomUUID(), CardGrant.Kind.TIMED, HOUR, ENTER, false, T0 + 1, one));
        assertNotNull(AccessControl.printCard(archive, owner, UUID.randomUUID(), CardGrant.Kind.TIMED, HOUR, ENTER, false, T0 + HOUR, one));
    }

    @Test void theDeadRecordsAreBoundedAndTheOldestGoFirst() {
        ServerPolicy roomy = policy.withCards(new CardPolicy(true, 32));
        UUID first = null;
        for (int i = 0; i < CardPolicy.MAX_RECORDS + 10; i++) {
            CardGrant card = AccessControl.printCard(archive, owner, UUID.randomUUID(), CardGrant.Kind.UNTIL_REVOKED, 0, ENTER, false, T0, roomy);
            if (first == null) first = card.token();
            AccessControl.revokeCard(archive, owner, card.number(), T0);
        }
        assertEquals(CardPolicy.MAX_RECORDS, archive.cards().size());
        assertFalse(archive.cards().containsKey(first), "the oldest dead record was dropped");
        assertEquals(CardPolicy.MAX_RECORDS + 10 + 1, archive.nextCardNumber(), "numbers are never reused");
    }

    @Test void permissionsAreClampedToTheGuestLimitAndAdminIsRefused() {
        Set<Permission> build = Set.of(Permission.ENTER, Permission.BUILD, Permission.STORAGE);
        CardGrant card = print(CardGrant.Kind.UNTIL_REVOKED, 0, build, false);
        enter(alex, card, T0);
        assertTrue(AccessControl.can(archive, alex, Permission.BUILD, T0, policy));
        assertTrue(AccessControl.can(archive, alex, Permission.STORAGE, T0, policy));
        assertFalse(AccessControl.can(archive, alex, Permission.INTERACT_BLOCK, T0, policy));
        assertFalse(AccessControl.can(archive, alex, Permission.ADMIN, T0, policy), "ADMIN is never delegable");
        ServerPolicy noBuild = new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 30L * 24 * HOUR, EnumSet.of(Permission.ENTER, Permission.STORAGE));
        assertFalse(AccessControl.can(archive, alex, Permission.BUILD, T0, noBuild), "shrinking the guest limit takes the right away from a card visit at once");
        assertTrue(AccessControl.can(archive, alex, Permission.STORAGE, T0, noBuild));
        assertThrows(ArchiveException.class, () -> print(CardGrant.Kind.UNTIL_REVOKED, 0, Set.of(Permission.ENTER, Permission.ADMIN), false));
        assertThrows(ArchiveException.class, () -> AccessControl.printCard(archive, owner, UUID.randomUUID(), CardGrant.Kind.UNTIL_REVOKED, 0, build, false, T0, noBuild), "a right the server forbids cannot be printed");
        assertThrows(ArchiveException.class, () -> print(CardGrant.Kind.UNTIL_REVOKED, 0, Set.of(Permission.BUILD), false), "ENTER is required");
    }

    @Test void aCardNeverGivesAdminEvenIfARecordSaysSo() {
        assertThrows(ArchiveException.class, () -> new CardGrant(UUID.randomUUID(), 1, CardGrant.Kind.UNTIL_REVOKED, 0, Long.MAX_VALUE, Invitation.allPermissions(), false, null, null, 0, false, false));
    }

    @Test void theServerSwitchStopsEveryCardAndEvictsItsVisitors() {
        CardGrant card = forever();
        enter(alex, card, T0);
        ServerPolicy off = policy.withCards(new CardPolicy(false, 8));
        assertEquals(AccessControl.EntryRefusal.CARDS_DISABLED, AccessControl.entryRefusal(archive, blake, T0, off, card.token()));
        assertEquals(List.of(alex), AccessControl.cardVisitorsToEvict(archive, off));
        assertFalse(AccessControl.can(archive, alex, Permission.ENTER, T0, off));
        assertThrows(ArchiveException.class, () -> AccessControl.printCard(archive, owner, UUID.randomUUID(), CardGrant.Kind.UNTIL_REVOKED, 0, ENTER, false, T0, off));
        assertNull(AccessControl.entryRefusal(archive, alex, T0, policy, card.token()), "back on: the card works again for its user");
        assertTrue(AccessControl.revokeCard(archive, owner, card.number(), T0).revoked(), "the owner can still revoke while the server has cards off");
    }

    @Test void anExplicitInvitationAlwaysWinsAndLeavesTheCardAlone() {
        AccessControl.invite(archive, owner, alex, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER, Permission.BUILD), T0, policy);
        CardGrant card = single();
        Visit visit = enter(alex, card, T0);
        assertNull(visit.cardToken(), "the invitation decided");
        assertTrue(visit.permissions().contains(Permission.BUILD));
        assertEquals(CardGrant.State.LIVE, fresh(card).state(T0), "the card was not spent");
        assertNull(fresh(card).boundTo());
    }

    @Test void aSpentInvitationFallsThroughToAValidCard() {
        AccessControl.invite(archive, owner, alex, Invitation.Kind.SINGLE_VISIT, 0, ENTER, T0, policy);
        enter(alex, forever(), T0);
        AccessControl.exit(archive, alex);
        CardGrant card = fresh(archive.cards().values().iterator().next());
        assertEquals(CardGrant.State.LIVE, card.state(T0), "the invitation decided the first visit, the card stayed unused");
        assertNull(AccessControl.entryRefusal(archive, alex, T0 + 1, policy, card.token()), "the spent invitation no longer blocks a card that works");
        Visit visit = enter(alex, card, T0 + 1);
        assertEquals(card.token(), visit.cardToken());
        assertTrue(AccessControl.can(archive, alex, Permission.ENTER, T0, policy));
        AccessControl.revokeCard(archive, owner, card.number(), T0 + 2);
        assertEquals(List.of(alex), AccessControl.cardVisitorsToEvict(archive, policy), "the card ends that visit even though a spent invitation is on file");
        assertFalse(AccessControl.can(archive, alex, Permission.ENTER, T0, policy));
    }

    @Test void aSpentInvitationAndADeadCardKeepTheInvitationRefusal() {
        AccessControl.invite(archive, owner, alex, Invitation.Kind.SINGLE_VISIT, 0, ENTER, T0, policy);
        enter(alex, forever(), T0);
        AccessControl.exit(archive, alex);
        CardGrant card = timed(HOUR);
        assertEquals(AccessControl.EntryRefusal.CARD_EXPIRED, AccessControl.entryRefusal(archive, alex, T0 + 2 * HOUR, policy, card.token()));
        assertEquals(AccessControl.EntryRefusal.USED, AccessControl.entryRefusal(archive, alex, T0 + 1, policy, UUID.randomUUID()), "an unknown token changes nothing");
    }

    @Test void anExpiredInvitationFallsThroughToAValidCard() {
        AccessControl.invite(archive, owner, alex, Invitation.Kind.TIMED, HOUR, ENTER, T0, policy);
        CardGrant card = forever();
        assertNull(AccessControl.entryRefusal(archive, alex, T0 + 2 * HOUR, policy, card.token()));
        assertEquals(AccessControl.EntryRefusal.EXPIRED, AccessControl.entryRefusal(archive, alex, T0 + 2 * HOUR, policy));
        assertEquals(card.token(), enter(alex, card, T0 + 2 * HOUR).cardToken());
    }

    @Test void aRevokedCardStillLetsATeammateInByTheirTeam() {
        var teams = new TeamAccessTest.FakeTeams();
        teams.join("p1", owner, TeamRelation.OWNER);
        teams.join("p1", alex, TeamRelation.MEMBER);
        ServerPolicy withTeam = policy.withTeams(new TeamPolicy(TeamPolicy.Mode.MEMBERS, TeamRelation.MEMBER, 16, teams));
        AccessControl.enableTeamAccess(archive, owner, withTeam);
        CardGrant card = forever();
        AccessControl.revokeCard(archive, owner, card.number(), T0);
        assertNull(AccessControl.entryRefusal(archive, alex, T0, withTeam, card.token()), "the team grant stands on its own");
        Visit visit = AccessControl.enter(archive, alex, "Alex", HOME, T0, withTeam, card.token());
        assertNull(visit.cardToken(), "it is a team visit, not a card visit");
        assertEquals(AccessControl.EntryRefusal.CARD_REVOKED, AccessControl.entryRefusal(archive, stranger, T0, withTeam, card.token()), "a stranger sees the card's own reason");
    }

    @Test void theOwnerAndAMissingTokenNeedNoCard() {
        assertNull(AccessControl.entryRefusal(archive, owner, T0, policy, UUID.randomUUID()));
        assertEquals(Invitation.allPermissions(), AccessControl.enter(archive, owner, "Owner", HOME, T0, policy, UUID.randomUUID()).permissions());
        assertNull(archive.activeVisits().get(owner).cardToken());
    }

    @Test void aCardVisitIsNotATeamVisit() {
        CardGrant card = forever();
        enter(alex, card, T0);
        assertTrue(AccessControl.teamVisitorsToEvict(archive, policy).isEmpty(), "the team sweep leaves card visitors alone");
        assertTrue(AccessControl.cardVisitorsToEvict(archive, policy).isEmpty());
    }

    @Test void aVisitorOfACardThatWasForgottenIsEvicted() {
        CardGrant card = forever();
        enter(alex, card, T0);
        AccessControl.revokeCard(archive, owner, card.number(), T0);
        AccessControl.exit(archive, alex);
        assertTrue(AccessControl.cardVisitorsToEvict(archive, policy).isEmpty());
        CardGrant second = forever();
        enter(blake, second, T0);
        AccessControl.revokeCard(archive, owner, second.number(), T0);
        AccessControl.forgetCard(archive, owner, second.number(), T0);
        assertEquals(List.of(blake), AccessControl.cardVisitorsToEvict(archive, policy), "no record means no card");
    }

    @Test void numbersAreOneBasedAndStable() {
        CardGrant a = forever(), b = forever();
        assertEquals(1, a.number());
        assertEquals(2, b.number());
        assertSame(fresh(b), AccessControl.cardByNumber(archive, 2));
        assertThrows(ArchiveException.class, () -> AccessControl.cardByNumber(archive, 3));
    }

    @Test void restoreDropsDuplicatesAndKeepsNumbersMoving() {
        CardGrant a = new CardGrant(UUID.randomUUID(), 5, CardGrant.Kind.UNTIL_REVOKED, 0, Long.MAX_VALUE, ENTER, false, null, null, 0, false, false);
        CardGrant dupToken = new CardGrant(a.token(), 6, CardGrant.Kind.UNTIL_REVOKED, 0, Long.MAX_VALUE, ENTER, false, null, null, 0, false, false);
        CardGrant dupNumber = new CardGrant(UUID.randomUUID(), 5, CardGrant.Kind.UNTIL_REVOKED, 0, Long.MAX_VALUE, ENTER, false, null, null, 0, false, false);
        Archive other = new ArchiveRegistry().claim(UUID.randomUUID(), "nullspace:classic");
        assertEquals(2, other.restoreCards(1, List.of(a, dupToken, dupNumber)));
        assertEquals(1, other.cards().size());
        assertEquals(6, other.nextCardNumber(), "above every number in use even when the saved counter was lower");
    }

    @Test void aHolderCardDropsABindingFoundInASave() {
        CardGrant odd = new CardGrant(UUID.randomUUID(), 1, CardGrant.Kind.UNTIL_REVOKED, 0, Long.MAX_VALUE, ENTER, true, alex, "Alex", 1, false, false);
        assertNull(odd.boundTo());
        assertNull(odd.boundName());
    }

    @Test void aSingleVisitRecordWithUsesIsSpent() {
        CardGrant odd = new CardGrant(UUID.randomUUID(), 1, CardGrant.Kind.SINGLE_VISIT, 0, Long.MAX_VALUE, ENTER, false, null, null, 1, false, false);
        assertEquals(CardGrant.State.USED, odd.state(0), "a save that lost the flag cannot revive a used single-visit card");
    }

    @Test void tokensAreUnique() {
        assertThrows(ArchiveException.class, () -> {
            UUID token = UUID.randomUUID();
            AccessControl.printCard(archive, owner, token, CardGrant.Kind.UNTIL_REVOKED, 0, ENTER, false, T0, policy);
            AccessControl.printCard(archive, owner, token, CardGrant.Kind.UNTIL_REVOKED, 0, ENTER, false, T0, policy);
        });
    }

    @Test void aLongNameIsCut() {
        CardGrant card = new CardGrant(UUID.randomUUID(), 1, CardGrant.Kind.UNTIL_REVOKED, 0, Long.MAX_VALUE, ENTER, false, null, null, 0, false, false)
            .used(alex, "AVeryLongNameThatCannotBeAPlayerName");
        assertEquals(CardGrant.MAX_NAME, card.boundName().length());
    }

    @Test void anOldSaveWithoutCardsHasNone() {
        assertTrue(archive.cards().isEmpty());
        assertEquals(1, archive.nextCardNumber());
        assertEquals(Visit.class, new Visit(HOME, 0, ENTER).getClass());
        assertNull(new Visit(HOME, 0, ENTER).cardToken());
    }
}
