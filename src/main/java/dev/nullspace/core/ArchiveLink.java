package dev.nullspace.core;

import java.util.UUID;

// decides whether a player's own archive shows up in a tempad that takes upgrades. it only lists; AccessControl still
// decides entry, so the upgrade widens nobody's access
public final class ArchiveLink {
    private ArchiveLink() {}

    public static final String UPGRADE_NAMESPACE = "nullspace";
    public static final String UPGRADE_PATH = "archive_link";

    public static boolean lists(boolean required, boolean upgradable, boolean installed, UUID archiveOwner, UUID viewer) {
        if (!required || !upgradable || installed) return true;

        if (archiveOwner == null || viewer == null) return false;
        return !archiveOwner.equals(viewer);
    }
}
