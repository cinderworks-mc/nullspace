package dev.nullspace.core;

import java.util.UUID;
import java.util.function.Function;

// two players are linked when either may enter the other's archive right now. tempad's screening device hands the two
// profiles over in swapped order, so the rule is symmetric
public final class ArchiveCircle {
    private ArchiveCircle() {}

    public static boolean linked(Function<UUID, Archive> find, UUID a, UUID b, long now, ServerPolicy policy) {
        if (find == null || a == null || b == null || policy == null || a.equals(b)) return false;
        return mayVisit(find, a, b, now, policy) || mayVisit(find, b, a, now, policy);
    }

    public record Standing(boolean inArchiveDimension, Archive archive) {
        public static final Standing OUTSIDE = new Standing(false, null);
        public static final Standing UNKNOWN = new Standing(true, null);
        public static Standing inside(Archive archive) { return archive == null ? UNKNOWN : new Standing(true, archive); }
    }

    // a holder standing in an archive the viewer may not enter stays hidden, or the coordinates would be listed while
    // the door is refused. a lookup that fails counts as hidden
    public static boolean visible(Function<UUID, Archive> find, Function<UUID, Standing> standing, UUID viewer, UUID holder, long now, ServerPolicy policy) {
        if (!linked(find, viewer, holder, now, policy) || standing == null) return false;
        Standing at = standing.apply(holder);
        if (at == null) return false;
        if (!at.inArchiveDimension()) return true;
        return at.archive() != null && AccessControl.entryRefusal(at.archive(), viewer, now, policy) == null;
    }

    private static boolean mayVisit(Function<UUID, Archive> find, UUID host, UUID guest, long now, ServerPolicy policy) {
        Archive archive = find.apply(host);
        return archive != null && archive.initialized() && archive.invitations().containsKey(guest) && AccessControl.mayEnter(archive, guest, now, policy);
    }
}
