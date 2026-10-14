package dev.nullspace.core;

public record PendingEdit(Kind kind, int roomId, int oldTier, int directionOrdinal, int newRoomId, int gridX, int gridZ, int oldHeightTier) {
    public enum Kind { EXPAND, ANNEX, REMOVE, RAISE, BASEMENT }

    public PendingEdit {
        if (kind == null || roomId < 0 || oldTier < 0 || oldTier > 3 || directionOrdinal < 0 || directionOrdinal > 3 || newRoomId < 0
            || oldHeightTier < 0 || oldHeightTier > 2) throw ArchiveException.corrupt("pending-edit");
    }
    public PendingEdit(Kind kind, int roomId, int oldTier, int directionOrdinal, int newRoomId, int gridX, int gridZ) {
        this(kind, roomId, oldTier, directionOrdinal, newRoomId, gridX, gridZ, 0);
    }
    public static PendingEdit expand(int roomId, int oldTier) { return new PendingEdit(Kind.EXPAND, roomId, oldTier, 0, 0, 0, 0); }
    public static PendingEdit raise(int roomId, int widthTier, int oldHeightTier) { return new PendingEdit(Kind.RAISE, roomId, widthTier, 0, 0, 0, 0, oldHeightTier); }
    public static PendingEdit basement() { return new PendingEdit(Kind.BASEMENT, 0, 0, 0, 0, 0, 0, 0); }
    public static PendingEdit annex(LayoutPlanner.AnnexPlan plan) {
        return new PendingEdit(Kind.ANNEX, plan.sourceId(), 0, plan.direction().ordinal(), plan.roomId(), plan.gridX(), plan.gridZ());
    }
    public static PendingEdit remove(int neighbourId, int leafId, int leafTier, Direction towardsLeaf, int gridX, int gridZ) {
        return new PendingEdit(Kind.REMOVE, neighbourId, leafTier, towardsLeaf.ordinal(), leafId, gridX, gridZ);
    }
    public static PendingEdit remove(int neighbourId, int leafId, int leafTier, int leafHeightTier, Direction towardsLeaf, int gridX, int gridZ) {
        return new PendingEdit(Kind.REMOVE, neighbourId, leafTier, towardsLeaf.ordinal(), leafId, gridX, gridZ, leafHeightTier);
    }
    public Direction direction() { return Direction.values()[directionOrdinal]; }

    public boolean alreadyApplied(Archive archive) {
        return switch (kind) {
            case EXPAND -> !archive.rooms().containsKey(roomId) || archive.room(roomId).tier() != oldTier;
            case RAISE -> !archive.rooms().containsKey(roomId) || archive.room(roomId).heightTier() != oldHeightTier;
            case ANNEX -> archive.rooms().containsKey(newRoomId);
            case REMOVE -> !archive.rooms().containsKey(newRoomId);
            case BASEMENT -> archive.basement().installed();
        };
    }
}
