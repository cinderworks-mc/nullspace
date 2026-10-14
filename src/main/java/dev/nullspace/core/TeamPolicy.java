package dev.nullspace.core;

import java.util.Locale;
import java.util.Objects;

// provider is null when no team mod is present. that means the feature is absent, not just off: no team row in the
// terminal and no grant is computed
public record TeamPolicy(Mode mode, TeamRelation minimumRank, int maxTeamSize, TeamProvider provider) {
    public enum Mode { OFF, MEMBERS, MEMBERS_AND_ALLIES }
    public static final TeamPolicy NONE = new TeamPolicy(Mode.OFF, TeamRelation.MEMBER, 16, null);
    public static final int MAX_TEAM_SIZE_LIMIT = 1024;
    public TeamPolicy {
        Objects.requireNonNull(mode);
        // the threshold for real members: member or officer. Anything else (a bad config) is the safe default.
        if (minimumRank != TeamRelation.OFFICER) minimumRank = TeamRelation.MEMBER;
        maxTeamSize = Math.max(1, Math.min(MAX_TEAM_SIZE_LIMIT, maxTeamSize));
    }
    public boolean active() { return provider != null && mode != Mode.OFF; }
    public boolean accepts(TeamRelation relation) {
        return switch (relation) {
            case OWNER, OFFICER -> true;
            case MEMBER -> minimumRank == TeamRelation.MEMBER;
            case ALLY -> mode == Mode.MEMBERS_AND_ALLIES;
            case NONE -> false;
        };
    }
    public static Mode parseMode(String text) {
        if (text == null) return Mode.MEMBERS;
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "off" -> Mode.OFF;
            case "members_and_allies", "members-and-allies", "membersandallies" -> Mode.MEMBERS_AND_ALLIES;
            default -> Mode.MEMBERS;
        };
    }
}
