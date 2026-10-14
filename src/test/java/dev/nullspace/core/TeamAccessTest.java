package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamAccessTest {
    private static final ReturnLocation HOME = new ReturnLocation("minecraft:overworld", 10, 70, -5, 90, 0);
    private static final Set<Permission> ENTER = Set.of(Permission.ENTER);

    static final class FakeTeams implements TeamProvider {
        final Map<UUID, String> teamOf = new HashMap<>();
        final Map<String, Map<UUID, TeamRelation>> ranks = new HashMap<>();
        final Map<String, String> names = new HashMap<>();
        boolean eligible = true;
        void join(String team, UUID player, TeamRelation rank) {
            if (rank != TeamRelation.ALLY) teamOf.put(player, team);
            ranks.computeIfAbsent(team, k -> new HashMap<>()).put(player, rank);
            names.putIfAbsent(team, "Party " + team);
        }
        void leave(UUID player) { String t = teamOf.remove(player); if (t != null) ranks.get(t).remove(player); }
        @Override public TeamRelation relation(UUID owner, UUID other) {
            String t = teamOf.get(owner);
            if (!eligible || t == null) return TeamRelation.NONE;
            return ranks.get(t).getOrDefault(other, TeamRelation.NONE);
        }
        @Override public String teamKey(UUID player) { return eligible ? teamOf.get(player) : null; }
        @Override public String teamName(UUID player) { String t = teamKey(player); return t == null ? null : names.get(t); }
        @Override public int teamSize(UUID player) {
            String t = teamKey(player);
            return t == null ? 0 : (int) ranks.get(t).values().stream().filter(TeamRelation::memberOrBetter).count();
        }
    }

    private final UUID owner = UUID.randomUUID(), mate = UUID.randomUUID(), officer = UUID.randomUUID(), ally = UUID.randomUUID(), stranger = UUID.randomUUID();
    private final FakeTeams teams = new FakeTeams();
    private final ArchiveRegistry registry = new ArchiveRegistry();
    private final Archive archive = registry.claim(owner, "nullspace:classic");

    private ServerPolicy policy(TeamPolicy.Mode mode, TeamRelation min) { return ServerPolicy.DEFAULT.withTeams(new TeamPolicy(mode, min, 16, teams)); }
    private ServerPolicy members() { return policy(TeamPolicy.Mode.MEMBERS, TeamRelation.MEMBER); }

    private void party() {
        teams.join("p1", owner, TeamRelation.OWNER);
        teams.join("p1", mate, TeamRelation.MEMBER);
        teams.join("p1", officer, TeamRelation.OFFICER);
        teams.join("p1", ally, TeamRelation.ALLY);
    }
    private void switchedOn(ServerPolicy policy) { party(); AccessControl.enableTeamAccess(archive, owner, policy); }

    @Test void nothingIsGrantedUntilTheOwnerSwitchesItOn() {
        party();
        assertFalse(AccessControl.mayEnter(archive, mate, 0, members()));
        assertEquals(AccessControl.EntryRefusal.NOT_INVITED, AccessControl.entryRefusal(archive, mate, 0, members()));
        assertEquals(AccessControl.TeamStatus.OFF, AccessControl.teamView(archive, members()).status());
    }

    @Test void whenSwitchedOnTeammatesStartWithEnterOnly() {
        switchedOn(members());
        assertTrue(AccessControl.mayEnter(archive, mate, 0, members()));
        assertNull(AccessControl.entryRefusal(archive, mate, 0, members()));
        AccessControl.enter(archive, mate, HOME, 1, members());
        assertTrue(AccessControl.can(archive, mate, Permission.ENTER, 0, members()));
        for (Permission p : Permission.values()) if (p != Permission.ENTER) assertFalse(AccessControl.can(archive, mate, p, 0, members()), p.name());
    }

    @Test void theOwnerRaisesTheTeamPermissionsLikeAGuestAndTheVisitFollowsLive() {
        switchedOn(members());
        AccessControl.enter(archive, mate, HOME, 1, members());
        assertFalse(AccessControl.can(archive, mate, Permission.BUILD, 0, members()));
        AccessControl.setTeamPermissions(archive, owner, EnumSet.of(Permission.ENTER, Permission.BUILD, Permission.STORAGE), members());
        assertTrue(AccessControl.can(archive, mate, Permission.BUILD, 0, members()), "the visit is not a snapshot for team visitors");
        assertTrue(AccessControl.can(archive, mate, Permission.STORAGE, 0, members()));
        assertFalse(AccessControl.can(archive, mate, Permission.INTERACT_BLOCK, 0, members()));
        AccessControl.setTeamPermissions(archive, owner, ENTER, members());
        assertFalse(AccessControl.can(archive, mate, Permission.BUILD, 0, members()), "lowering takes effect inside the visit too");
    }

    @Test void adminCanNeverBeGrantedToATeam() {
        switchedOn(members());
        assertThrows(ArchiveException.class, () -> AccessControl.setTeamPermissions(archive, owner, EnumSet.of(Permission.ENTER, Permission.ADMIN), members()));
        assertThrows(ArchiveException.class, () -> new TeamAccess(true, EnumSet.of(Permission.ENTER, Permission.ADMIN), "p1"));
        AccessControl.enter(archive, mate, HOME, 1, members());
        assertFalse(AccessControl.can(archive, mate, Permission.ADMIN, 0, members()));
    }

    @Test void teamPermissionsNeedEnterAndStayWithinTheServersGuestLimit() {
        switchedOn(members());
        assertThrows(ArchiveException.class, () -> AccessControl.setTeamPermissions(archive, owner, EnumSet.of(Permission.BUILD), members()));
        ServerPolicy noBuild = new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 1000, Set.of(Permission.ENTER, Permission.STORAGE)).withTeams(members().teams());
        assertThrows(ArchiveException.class, () -> AccessControl.setTeamPermissions(archive, owner, EnumSet.of(Permission.ENTER, Permission.BUILD), noBuild));
    }

    @Test void shrinkingTheServersGuestLimitRevokesTheTeamGrantAtOnce() {
        switchedOn(members());
        AccessControl.setTeamPermissions(archive, owner, EnumSet.of(Permission.ENTER, Permission.BUILD, Permission.STORAGE), members());
        AccessControl.enter(archive, mate, HOME, 1, members());
        ServerPolicy noBuild = new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 1000, Set.of(Permission.ENTER, Permission.STORAGE)).withTeams(members().teams());
        assertFalse(AccessControl.can(archive, mate, Permission.BUILD, 0, noBuild));
        assertTrue(AccessControl.can(archive, mate, Permission.STORAGE, 0, noBuild));
        assertEquals(Set.of(Permission.ENTER, Permission.STORAGE), AccessControl.teamGrant(archive, mate, noBuild).permissions());
        ServerPolicy noEnter = new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 1000, Set.of(Permission.STORAGE)).withTeams(members().teams());
        assertFalse(AccessControl.mayEnter(archive, mate, 2, noEnter), "no ENTER in the guest limit switches visits off, teams included");
        assertEquals(List.of(mate), AccessControl.teamVisitorsToEvict(archive, noEnter));
    }

    @Test void relationsByModeAndMinimumRank() {
        party();
        teams.join("p1", UUID.randomUUID(), TeamRelation.NONE);
        for (var mode : TeamPolicy.Mode.values()) for (var min : List.of(TeamRelation.MEMBER, TeamRelation.OFFICER)) {
            ServerPolicy p = policy(mode, min);
            Archive a = new ArchiveRegistry().claim(owner, "nullspace:classic");
            if (mode != TeamPolicy.Mode.OFF) AccessControl.enableTeamAccess(a, owner, p);
            else a.restoreTeamAccess(new TeamAccess(true, ENTER, "p1"));
            String label = mode + "/" + min;
            assertTrue(AccessControl.mayEnter(a, owner, 0, p), label);
            assertEquals(mode != TeamPolicy.Mode.OFF, AccessControl.mayEnter(a, officer, 0, p), "officer " + label);
            assertEquals(mode != TeamPolicy.Mode.OFF && min == TeamRelation.MEMBER, AccessControl.mayEnter(a, mate, 0, p), "member " + label);
            assertEquals(mode == TeamPolicy.Mode.MEMBERS_AND_ALLIES, AccessControl.mayEnter(a, ally, 0, p), "ally " + label);
            assertFalse(AccessControl.mayEnter(a, stranger, 0, p), "stranger " + label);
        }
    }

    @Test void anInvitedOrEnemyOrUnknownPlayerNeverGetsIn() {
        switchedOn(members());
        // a provider maps FTB's INVITED (given to strangers of a free-to-join party) and ENEMY to NONE: that is all a provider may say here.
        assertFalse(AccessControl.mayEnter(archive, stranger, 0, members()));
        assertFalse(AccessControl.mayEnter(archive, UUID.randomUUID(), 0, members()));
        assertFalse(TeamPolicy.NONE.accepts(TeamRelation.NONE));
    }

    @Test void anExplicitInvitationReplacesTheTeamGrantBothWays() {
        switchedOn(members());
        AccessControl.setTeamPermissions(archive, owner, EnumSet.of(Permission.ENTER, Permission.BUILD), members());
        AccessControl.invite(archive, owner, mate, Invitation.Kind.PERMANENT, 0, ENTER, 0, members());
        AccessControl.enter(archive, mate, HOME, 1, members());
        assertFalse(AccessControl.can(archive, mate, Permission.BUILD, 0, members()), "the narrower explicit invitation wins");
        AccessControl.exit(archive, mate);
        AccessControl.setPermissions(archive, owner, mate, EnumSet.of(Permission.ENTER, Permission.INTERACT_ENTITY), members());
        AccessControl.enter(archive, mate, HOME, 2, members());
        assertTrue(AccessControl.can(archive, mate, Permission.INTERACT_ENTITY, 0, members()));
        assertFalse(AccessControl.can(archive, mate, Permission.BUILD, 0, members()), "replaces, not unions");
        // and the teammate keeps the explicit invitation when the team link goes
        teams.leave(mate);
        assertTrue(AccessControl.mayEnter(archive, mate, 3, members()));
        assertEquals(List.of(), AccessControl.teamVisitorsToEvict(archive, members()));
    }

    @Test void anExpiredOrSpentExplicitInvitationIsNotRescuedByTheTeamAndStaysTheLastWord() {
        switchedOn(members());
        AccessControl.invite(archive, owner, mate, Invitation.Kind.SINGLE_VISIT, 0, ENTER, 0, members());
        AccessControl.enter(archive, mate, HOME, 1, members());
        AccessControl.exit(archive, mate);
        assertEquals(AccessControl.EntryRefusal.USED, AccessControl.entryRefusal(archive, mate, 2, members()), "an explicit invitation decides, used or not");
    }

    @Test void enteringAsATeammateNeverTouchesTheInvitations() {
        switchedOn(members());
        assertTrue(archive.invitations().isEmpty());
        Visit visit = AccessControl.enter(archive, mate, HOME, 1, members());
        assertTrue(archive.invitations().isEmpty(), "the pseudo-invitation is computed and never stored");
        assertEquals(ENTER, visit.permissions());
        assertSame(visit, AccessControl.enter(archive, mate, HOME, 2, members()));
        AccessControl.exit(archive, mate);
        assertTrue(archive.invitations().isEmpty());
        assertTrue(AccessControl.mayEnter(archive, mate, 3, members()), "never consumed: a teammate may come back");
        AccessControl.enter(archive, mate, HOME, 4, members());
        AccessControl.exit(archive, mate);
        assertTrue(archive.invitations().isEmpty());
    }

    @Test void enterWithoutAnyGrantIsRefusedNotAnNullPointer() {
        party();
        assertThrows(ArchiveSecurityException.class, () -> AccessControl.enter(archive, mate, HOME, 1, members()));
        assertTrue(archive.activeVisits().isEmpty());
    }

    @Test void guestCountDoesNotIncludeTeammates() {
        switchedOn(members());
        ServerPolicy two = new ServerPolicy(false, false, true, true, 8, 81, 9, 1, 1000, ServerPolicy.DEFAULT.allowedGuestPermissions()).withTeams(members().teams());
        AccessControl.invite(archive, owner, stranger, Invitation.Kind.PERMANENT, 0, ENTER, 0, two);
        assertTrue(AccessControl.mayEnter(archive, mate, 0, two));
        assertTrue(AccessControl.mayEnter(archive, officer, 0, two));
        assertEquals(1, archive.invitations().size());
    }

    @Test void theTeamSizeCapRefusesTheGrant() {
        switchedOn(members());
        for (int i = 0; i < 20; i++) teams.join("p1", UUID.randomUUID(), TeamRelation.MEMBER);
        assertEquals(AccessControl.TeamStatus.TOO_LARGE, AccessControl.teamView(archive, members()).status());
        assertFalse(AccessControl.mayEnter(archive, mate, 0, members()));
        ServerPolicy big = ServerPolicy.DEFAULT.withTeams(new TeamPolicy(TeamPolicy.Mode.MEMBERS, TeamRelation.MEMBER, 64, teams));
        assertTrue(AccessControl.mayEnter(archive, mate, 0, big));
        Archive fresh = new ArchiveRegistry().claim(owner, "nullspace:classic");
        var e = assertThrows(ArchiveException.class, () -> AccessControl.enableTeamAccess(fresh, owner, members()));
        assertEquals("error.nullspace.team.too_large", e.key());
    }

    @Test void theGrantIsBoundToTheTeamTheOwnerConsentedTo() {
        switchedOn(members());
        AccessControl.enter(archive, mate, HOME, 1, members());
        // the owner joins somebody else's party
        teams.leave(owner);
        teams.join("p2", owner, TeamRelation.MEMBER);
        teams.join("p2", stranger, TeamRelation.MEMBER);
        assertEquals(AccessControl.TeamStatus.SUSPENDED, AccessControl.teamView(archive, members()).status());
        assertFalse(AccessControl.mayEnter(archive, stranger, 2, members()), "the new party gains nothing");
        assertFalse(AccessControl.mayEnter(archive, mate, 2, members()), "the old party loses it");
        assertEquals(List.of(mate), AccessControl.teamVisitorsToEvict(archive, members()));
        // the owner re-confirms for the new party
        AccessControl.enableTeamAccess(archive, owner, members());
        assertTrue(AccessControl.mayEnter(archive, stranger, 3, members()));
        assertFalse(AccessControl.mayEnter(archive, mate, 3, members()));
    }

    @Test void goingSoloSuspendsAndRejoiningTheSamePartyResumes() {
        switchedOn(members());
        teams.leave(owner);
        assertEquals(AccessControl.TeamStatus.NO_TEAM, AccessControl.teamView(archive, members()).status());
        assertFalse(AccessControl.mayEnter(archive, mate, 0, members()));
        teams.join("p1", owner, TeamRelation.OWNER);
        assertTrue(AccessControl.mayEnter(archive, mate, 0, members()));
    }

    @Test void theOwnerNeverNeedsATeamAndAnOwnerSelfGrantIsNotComputed() {
        switchedOn(members());
        assertTrue(AccessControl.mayEnter(archive, owner, 0, members()));
        assertNull(AccessControl.teamGrant(archive, owner, members()));
        assertTrue(AccessControl.can(archive, owner, Permission.ADMIN, 0, members()));
    }

    @Test void aSoloOwnerCannotSwitchItOnAndAServerWithoutTheFeatureRefuses() {
        var e = assertThrows(ArchiveException.class, () -> AccessControl.enableTeamAccess(archive, owner, members()));
        assertEquals("error.nullspace.team.no_team", e.key());
        var off = assertThrows(ArchiveException.class, () -> AccessControl.enableTeamAccess(archive, owner, ServerPolicy.DEFAULT));
        assertEquals("error.nullspace.team.unavailable", off.key());
        var modeOff = assertThrows(ArchiveException.class, () -> AccessControl.enableTeamAccess(archive, owner, policy(TeamPolicy.Mode.OFF, TeamRelation.MEMBER)));
        assertEquals("error.nullspace.team.unavailable", modeOff.key());
    }

    @Test void ineligibleTeamsAreNoTeams() {
        switchedOn(members());
        teams.eligible = false; // e.g. the owner's team turned out to be a server team while those are refused, or the mod's data is not loaded
        assertFalse(AccessControl.mayEnter(archive, mate, 0, members()));
        assertEquals(AccessControl.TeamStatus.NO_TEAM, AccessControl.teamView(archive, members()).status());
    }

    @Test void switchingOffByTheOwnerOrTheServerRevokesAndEvicts() {
        switchedOn(members());
        AccessControl.enter(archive, mate, HOME, 1, members());
        AccessControl.enter(archive, officer, HOME, 1, members());
        assertEquals(List.of(), AccessControl.teamVisitorsToEvict(archive, members()));
        assertEquals(Set.of(mate, officer), Set.copyOf(AccessControl.teamVisitorsToEvict(archive, policy(TeamPolicy.Mode.OFF, TeamRelation.MEMBER))), "mode off");
        assertEquals(Set.of(mate, officer), Set.copyOf(AccessControl.teamVisitorsToEvict(archive, ServerPolicy.DEFAULT)), "mod absent");
        AccessControl.disableTeamAccess(archive, owner);
        assertEquals(Set.of(mate, officer), Set.copyOf(AccessControl.teamVisitorsToEvict(archive, members())), "owner switch");
        assertFalse(AccessControl.can(archive, mate, Permission.ENTER, 0, members()));
        assertEquals(Set.of(Permission.ENTER), archive.teamAccess().permissions());
    }

    @Test void leavingKickingAndDemotionAreCaughtBySweepingTheVisits() {
        switchedOn(members());
        AccessControl.enter(archive, mate, HOME, 1, members());
        AccessControl.enter(archive, officer, HOME, 1, members());
        AccessControl.enter(archive, ally, HOME, 1, policy(TeamPolicy.Mode.MEMBERS_AND_ALLIES, TeamRelation.MEMBER));
        ServerPolicy officersOnly = policy(TeamPolicy.Mode.MEMBERS_AND_ALLIES, TeamRelation.OFFICER);
        assertEquals(Set.of(mate), Set.copyOf(AccessControl.teamVisitorsToEvict(archive, officersOnly)), "a member below the minimum rank goes, officer and ally stay");
        teams.leave(officer);
        assertEquals(Set.of(mate, officer), Set.copyOf(AccessControl.teamVisitorsToEvict(archive, officersOnly)));
        assertEquals(Set.of(officer, ally), Set.copyOf(AccessControl.teamVisitorsToEvict(archive, members())), "allies leave when the mode drops to members");
        // the evicted are removed like a removed guest
        for (UUID id : AccessControl.teamVisitorsToEvict(archive, members())) {
            var ended = AccessControl.exit(archive, id);
            assertEquals(AccessControl.Departure.EVICT_NOW, AccessControl.departure(ended, true, true));
            assertEquals(AccessControl.Departure.RETURN_AT_LOGIN, AccessControl.departure(ended, false, false));
        }
        assertEquals(Set.of(mate), archive.activeVisits().keySet());
        assertEquals(List.of(), AccessControl.teamVisitorsToEvict(archive, members()));
    }

    @Test void explicitGuestsAndTheOwnerAreNeverSweptOut() {
        switchedOn(members());
        AccessControl.invite(archive, owner, stranger, Invitation.Kind.PERMANENT, 0, ENTER, 0, members());
        AccessControl.enter(archive, stranger, HOME, 1, members());
        AccessControl.enter(archive, owner, HOME, 1, members());
        assertEquals(List.of(), AccessControl.teamVisitorsToEvict(archive, ServerPolicy.DEFAULT));
    }

    @Test void theFeatureIsInertWithoutAProviderOrWithItOff() {
        party();
        archive.restoreTeamAccess(new TeamAccess(true, ENTER, "p1"));
        assertFalse(AccessControl.mayEnter(archive, mate, 0, ServerPolicy.DEFAULT));
        assertFalse(AccessControl.mayEnter(archive, mate, 0, policy(TeamPolicy.Mode.OFF, TeamRelation.MEMBER)));
        assertEquals(AccessControl.TeamStatus.UNAVAILABLE, AccessControl.teamView(archive, ServerPolicy.DEFAULT).status());
    }

    @Test void teamRespawnRightFollowsTheGrant() {
        switchedOn(members());
        assertFalse(AccessControl.invitedSpawnRight(archive, mate, members()));
        AccessControl.setTeamPermissions(archive, owner, EnumSet.of(Permission.ENTER, Permission.SET_SPAWN), members());
        assertTrue(AccessControl.invitedSpawnRight(archive, mate, members()));
        assertFalse(AccessControl.invitedSpawnRight(archive, stranger, members()));
    }

    @Test void restoredArchiveKeepsTheSwitchAndATeamVisit() {
        switchedOn(members());
        AccessControl.setTeamPermissions(archive, owner, EnumSet.of(Permission.ENTER, Permission.BUILD), members());
        AccessControl.enter(archive, mate, HOME, 1, members());
        Archive copy = Archive.restore(archive.owner(), archive.allocation(), archive.starterPreset(), true, false, archive.settings(),
            archive.rooms(), archive.invitations(), archive.activeVisits(), archive.presets());
        copy.restoreTeamAccess(archive.teamAccess());
        assertTrue(copy.teamAccess().enabled());
        assertEquals("p1", copy.teamAccess().consentKey());
        assertTrue(AccessControl.can(copy, mate, Permission.BUILD, 0, members()));
    }

    @Test void policyParsingIsForgiving() {
        assertEquals(TeamPolicy.Mode.OFF, TeamPolicy.parseMode("OFF"));
        assertEquals(TeamPolicy.Mode.MEMBERS_AND_ALLIES, TeamPolicy.parseMode("members_and_allies"));
        assertEquals(TeamPolicy.Mode.MEMBERS, TeamPolicy.parseMode("whatever"));
        assertEquals(TeamRelation.MEMBER, new TeamPolicy(TeamPolicy.Mode.MEMBERS, TeamRelation.ALLY, 0, teams).minimumRank());
        assertEquals(1, new TeamPolicy(TeamPolicy.Mode.MEMBERS, TeamRelation.MEMBER, -5, teams).maxTeamSize());
    }

    @Test void theSweepRunsOnRequestAndEveryTwentyTicks() {
        TeamSweep sweep = new TeamSweep();
        assertTrue(sweep.due(0));
        assertFalse(sweep.due(1));
        sweep.request();
        assertTrue(sweep.due(2), "an event asks for a check on the next tick");
        assertFalse(sweep.due(3), "once per request");
        assertTrue(sweep.due(20));
        assertFalse(sweep.due(21));
        assertTrue(sweep.due(40));
    }
}
