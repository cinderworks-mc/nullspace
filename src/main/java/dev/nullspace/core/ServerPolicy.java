package dev.nullspace.core;

import java.util.Set;

// maxRoomWidth and maxCeilingHeight only veto future upgrades; a room that already has the geometry keeps it. both
// snap down to the nearest step (widths 9, 13, 17, 21; heights 6, 9, 12) so a bad config cannot throw
public record ServerPolicy(boolean allowKeepInventory, boolean allowKeepXp, boolean allowSpawn,
                           boolean allowAnchors, int maxAnchoredArchives, int maxChunksPerArchive,
                           int maxRooms, int maxGuests, long maxInvitationDurationMillis,
                           Set<Permission> allowedGuestPermissions, boolean allowRecall, int recallMinutes,
                           int maxRoomWidth, int maxCeilingHeight, TeamPolicy teams, CardPolicy cards) {
    public static final ServerPolicy DEFAULT = new ServerPolicy(false, false, true, true,
        8, 81, 9, 32, 30L * 24 * 60 * 60 * 1000,
        Set.of(Permission.ENTER, Permission.STORAGE, Permission.INTERACT_BLOCK, Permission.BUILD,
            Permission.INTERACT_ENTITY, Permission.SET_SPAWN));
    public ServerPolicy(boolean allowKeepInventory, boolean allowKeepXp, boolean allowSpawn,
                        boolean allowAnchors, int maxAnchoredArchives, int maxChunksPerArchive,
                        int maxRooms, int maxGuests, long maxInvitationDurationMillis,
                        Set<Permission> allowedGuestPermissions) {
        this(allowKeepInventory, allowKeepXp, allowSpawn, allowAnchors, maxAnchoredArchives, maxChunksPerArchive, maxRooms,
            maxGuests, maxInvitationDurationMillis, allowedGuestPermissions, true, Recall.DEFAULT_MINUTES);
    }
    public ServerPolicy(boolean allowKeepInventory, boolean allowKeepXp, boolean allowSpawn,
                        boolean allowAnchors, int maxAnchoredArchives, int maxChunksPerArchive,
                        int maxRooms, int maxGuests, long maxInvitationDurationMillis,
                        Set<Permission> allowedGuestPermissions, boolean allowRecall, int recallMinutes) {
        this(allowKeepInventory, allowKeepXp, allowSpawn, allowAnchors, maxAnchoredArchives, maxChunksPerArchive, maxRooms,
            maxGuests, maxInvitationDurationMillis, allowedGuestPermissions, allowRecall, recallMinutes, Room.MAX_WIDTH, Room.MAX_HEIGHT);
    }
    public ServerPolicy(boolean allowKeepInventory, boolean allowKeepXp, boolean allowSpawn,
                        boolean allowAnchors, int maxAnchoredArchives, int maxChunksPerArchive,
                        int maxRooms, int maxGuests, long maxInvitationDurationMillis,
                        Set<Permission> allowedGuestPermissions, boolean allowRecall, int recallMinutes,
                        int maxRoomWidth, int maxCeilingHeight) {
        this(allowKeepInventory, allowKeepXp, allowSpawn, allowAnchors, maxAnchoredArchives, maxChunksPerArchive, maxRooms,
            maxGuests, maxInvitationDurationMillis, allowedGuestPermissions, allowRecall, recallMinutes, maxRoomWidth, maxCeilingHeight, TeamPolicy.NONE, CardPolicy.DEFAULT);
    }
    public ServerPolicy(boolean allowKeepInventory, boolean allowKeepXp, boolean allowSpawn,
                        boolean allowAnchors, int maxAnchoredArchives, int maxChunksPerArchive,
                        int maxRooms, int maxGuests, long maxInvitationDurationMillis,
                        Set<Permission> allowedGuestPermissions, boolean allowRecall, int recallMinutes,
                        int maxRoomWidth, int maxCeilingHeight, TeamPolicy teams) {
        this(allowKeepInventory, allowKeepXp, allowSpawn, allowAnchors, maxAnchoredArchives, maxChunksPerArchive, maxRooms,
            maxGuests, maxInvitationDurationMillis, allowedGuestPermissions, allowRecall, recallMinutes, maxRoomWidth, maxCeilingHeight, teams, CardPolicy.DEFAULT);
    }
    public ServerPolicy withTeams(TeamPolicy other) {
        return new ServerPolicy(allowKeepInventory, allowKeepXp, allowSpawn, allowAnchors, maxAnchoredArchives, maxChunksPerArchive, maxRooms,
            maxGuests, maxInvitationDurationMillis, allowedGuestPermissions, allowRecall, recallMinutes, maxRoomWidth, maxCeilingHeight, other, cards);
    }
    public ServerPolicy withCards(CardPolicy other) {
        return new ServerPolicy(allowKeepInventory, allowKeepXp, allowSpawn, allowAnchors, maxAnchoredArchives, maxChunksPerArchive, maxRooms,
            maxGuests, maxInvitationDurationMillis, allowedGuestPermissions, allowRecall, recallMinutes, maxRoomWidth, maxCeilingHeight, teams, other);
    }
    public ServerPolicy {
        if (teams == null) teams = TeamPolicy.NONE;
        if (cards == null) cards = CardPolicy.DEFAULT;
        if (maxAnchoredArchives < 0 || maxChunksPerArchive < 1 || maxChunksPerArchive > 4096
            || maxRooms < 1 || maxRooms > 256 || maxGuests < 0 || maxGuests > 4096
            || maxInvitationDurationMillis < 1) throw ArchiveException.corrupt("server-policy");
        allowedGuestPermissions = Invitation.guestPermissions(allowedGuestPermissions);
        recallMinutes = Recall.clampMinutes(recallMinutes);
        maxRoomWidth = snapDown(maxRoomWidth, Room.widths());
        maxCeilingHeight = snapDown(maxCeilingHeight, Room.heights());
    }
    private static int snapDown(int value, int[] steps) {
        int best = steps[0];
        for (int step : steps) if (step <= value) best = step;
        return best;
    }
    public ArchiveSettings effective(ArchiveSettings requested) {
        return new ArchiveSettings(allowKeepInventory && requested.keepInventory(),
            allowKeepXp && requested.keepXp(), allowSpawn && requested.allowSpawn());
    }
}
