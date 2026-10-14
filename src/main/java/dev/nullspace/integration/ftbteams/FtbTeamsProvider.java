package dev.nullspace.integration.ftbteams;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import dev.nullspace.core.TeamProvider;
import dev.nullspace.core.TeamRelation;
import dev.nullspace.server.ArchiveService;
import dev.nullspace.server.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

// ftb teams as a TeamProvider. only this package names ftb teams or architectury types
public final class FtbTeamsProvider implements TeamProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("nullspace");
    private boolean complained;

    public static void init() {
        ArchiveService.registerTeamProvider(new FtbTeamsProvider());
        // none of these handlers does anything but set a flag: FTB is in the middle of changing its data when they run.
        TeamEvent.PLAYER_LEFT_PARTY.register(e -> ArchiveService.requestTeamRecheck());
        TeamEvent.PLAYER_JOINED_PARTY.register(e -> ArchiveService.requestTeamRecheck());
        TeamEvent.PLAYER_CHANGED.register(e -> ArchiveService.requestTeamRecheck());
        TeamEvent.ADD_ALLY.register(e -> ArchiveService.requestTeamRecheck());
        TeamEvent.REMOVE_ALLY.register(e -> ArchiveService.requestTeamRecheck());
        TeamEvent.OWNERSHIP_TRANSFERRED.register(e -> ArchiveService.requestTeamRecheck());
        TeamEvent.DELETED.register(e -> ArchiveService.requestTeamRecheck());
    }

    @Override public boolean ready() {
        try { return FTBTeamsAPI.api().isManagerLoaded(); }
        catch (LinkageError | RuntimeException t) { return failed(t, false); }
    }

    @Override public TeamRelation relation(UUID owner, UUID other) {
        try {
            Team team = eligibleTeam(owner);
            if (team == null) return TeamRelation.NONE;
            TeamRank rank = team.getRankForPlayer(other);
            if (rank == null) return TeamRelation.NONE;
            if (rank == TeamRank.OWNER) return TeamRelation.OWNER;
            if (rank == TeamRank.OFFICER) return TeamRelation.OFFICER;
            if (rank == TeamRank.MEMBER) return TeamRelation.MEMBER;
            if (rank == TeamRank.ALLY) return TeamRelation.ALLY;
            return TeamRelation.NONE; // INVITED, NONE, ENEMY
        } catch (LinkageError | RuntimeException t) { return failed(t, TeamRelation.NONE); }
    }

    @Override public String teamKey(UUID player) {
        try {
            Team team = eligibleTeam(player);
            return team == null ? null : team.getId().toString();
        } catch (LinkageError | RuntimeException t) { return failed(t, null); }
    }

    @Override public String teamName(UUID player) {
        try {
            Team team = eligibleTeam(player);
            return team == null ? null : team.getName().getString();
        } catch (LinkageError | RuntimeException t) { return failed(t, null); }
    }

    @Override public int teamSize(UUID player) {
        try {
            Team team = eligibleTeam(player);
            return team == null ? 0 : team.getMembers().size();
        } catch (LinkageError | RuntimeException t) { return failed(t, 0); }
    }

    private static Team eligibleTeam(UUID player) {
        FTBTeamsAPI.API api = FTBTeamsAPI.api();
        if (!api.isManagerLoaded()) return null;
        Optional<Team> found = api.getManager().getTeamForPlayerID(player);
        if (found.isEmpty()) return null;
        Team team = found.get();
        if (!team.isValid()) return null;
        if (team.isPartyTeam()) return team;
        if (team.isServerTeam() && ServerConfig.TEAMS_SERVER_TEAMS.get()) return team;
        return null;
    }

    private <T> T failed(Throwable t, T answer) {
        if (!complained) { complained = true; LOGGER.warn("FTB Teams could not be read (unsupported version?); team access grants nothing", t); }
        return answer;
    }
}
