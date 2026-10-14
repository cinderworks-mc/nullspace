package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// removing ENTER from access.allowedGuestPermissions switches guest visits off
class GuestVisitsOffTest {
    private final UUID owner = UUID.randomUUID(), guest = UUID.randomUUID();
    private static final long NOW = 1_000_000L;

    private static ServerPolicy withoutEnter() {
        ServerPolicy d = ServerPolicy.DEFAULT;
        Set<Permission> p = EnumSet.copyOf(d.allowedGuestPermissions());
        p.remove(Permission.ENTER);
        return new ServerPolicy(d.allowKeepInventory(), d.allowKeepXp(), d.allowSpawn(), d.allowAnchors(), d.maxAnchoredArchives(), d.maxChunksPerArchive(),
            d.maxRooms(), d.maxGuests(), d.maxInvitationDurationMillis(), p, d.allowRecall(), d.recallMinutes(), d.maxRoomWidth(), d.maxCeilingHeight(), d.teams(), d.cards());
    }

    @Test void anExistingInvitationStopsWorkingWhenEnterLeavesTheServerList() {
        Archive a = new ArchiveRegistry().claim(owner, "nullspace:classic");
        AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER, Permission.BUILD), NOW, ServerPolicy.DEFAULT);
        assertNull(AccessControl.entryRefusal(a, guest, NOW, ServerPolicy.DEFAULT));
        assertEquals(AccessControl.EntryRefusal.DISABLED, AccessControl.entryRefusal(a, guest, NOW, withoutEnter()));
    }

    @Test void newInvitationsCannotAskForEnterOnceItIsOff() {
        Archive a = new ArchiveRegistry().claim(owner, "nullspace:classic");
        ArchiveException e = assertThrows(ArchiveException.class,
            () -> AccessControl.invite(a, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), NOW, withoutEnter()));
        assertEquals("error.nullspace.invite.forbidden_permissions", e.key());
    }

    @Test void theOwnerAndAStrangerAreUnaffected() {
        Archive a = new ArchiveRegistry().claim(owner, "nullspace:classic");
        assertNull(AccessControl.entryRefusal(a, owner, NOW, withoutEnter()));
        assertEquals(AccessControl.EntryRefusal.NOT_INVITED, AccessControl.entryRefusal(a, UUID.randomUUID(), NOW, withoutEnter()));
    }
}
