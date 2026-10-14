package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreExceptionKeysTest {
    private static final long HOUR = 3_600_000L;
    private static final ServerPolicy POLICY = new ServerPolicy(false, false, true, true, 4, 81, 9, 1, 24 * HOUR,
        Set.of(Permission.ENTER, Permission.BUILD));
    private static final ReturnLocation HOME = new ReturnLocation("minecraft:overworld", 1, 70, 1, 0, 0);

    private final ArchiveRegistry registry = new ArchiveRegistry();
    private final Archive archive = registry.claim(UUID.randomUUID(), "nullspace:classic");
    private final UUID owner = archive.owner();

    @Test void exceptionsKeepTheirSuperclassesAndExposeKeyAndArgs() {
        var illegal = new ArchiveException("error.nullspace.invite.guest_limit", 3);
        assertInstanceOf(IllegalArgumentException.class, illegal);
        assertEquals("error.nullspace.invite.guest_limit", illegal.key());
        assertArrayEquals(new Object[] {3}, illegal.args());
        var security = new ArchiveSecurityException("error.nullspace.owner_only");
        assertInstanceOf(SecurityException.class, security);
        assertEquals("error.nullspace.owner_only", security.key());
        assertEquals(0, security.args().length);
        assertEquals("error.nullspace.corrupt", ArchiveException.corrupt("room").key());
        assertArrayEquals(new Object[] {"room"}, ArchiveException.corrupt("room").args());
    }

    @Test void argsAreCopied() {
        var e = new ArchiveException("error.nullspace.room.limit", 9);
        e.args()[0] = 99;
        assertEquals(9, e.args()[0]);
    }

    @Test void nonOwnerIsRefusedWithTheOwnerOnlyKey() {
        var e = assertThrows(ArchiveSecurityException.class, () -> archive.requireOwner(UUID.randomUUID()));
        assertEquals("error.nullspace.owner_only", e.key());
    }

    @Test void invitationRefusalsNameTheirReasonAndNumbers() {
        UUID guest = UUID.randomUUID();
        var self = assertThrows(ArchiveException.class, () -> AccessControl.invite(archive, owner, owner, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, POLICY));
        assertEquals("error.nullspace.invite.self", self.key());
        var forbidden = assertThrows(ArchiveException.class, () -> AccessControl.invite(archive, owner, guest, Invitation.Kind.PERMANENT, 0,
            EnumSet.of(Permission.ENTER, Permission.INTERACT_ENTITY), 0, POLICY));
        assertEquals("error.nullspace.invite.forbidden_permissions", forbidden.key());
        var admin = assertThrows(ArchiveException.class, () -> AccessControl.invite(archive, owner, guest, Invitation.Kind.PERMANENT, 0,
            EnumSet.of(Permission.ENTER, Permission.ADMIN), 0, POLICY));
        assertEquals("error.nullspace.invite.admin", admin.key());
        var duration = assertThrows(ArchiveException.class, () -> AccessControl.invite(archive, owner, guest, Invitation.Kind.TIMED, 48 * HOUR, Set.of(Permission.ENTER), 0, POLICY));
        assertEquals("error.nullspace.invite.duration", duration.key());
        assertArrayEquals(new Object[] {24L}, duration.args(), "the limit is given in hours");
        AccessControl.invite(archive, owner, guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, POLICY);
        var full = assertThrows(ArchiveException.class, () -> AccessControl.invite(archive, owner, UUID.randomUUID(), Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, POLICY));
        assertEquals("error.nullspace.invite.guest_limit", full.key());
        assertArrayEquals(new Object[] {1}, full.args());
    }

    @Test void entryRefusalsAreDistinct() {
        UUID stranger = UUID.randomUUID(), timed = UUID.randomUUID(), single = UUID.randomUUID();
        archive.markInitialized();
        ServerPolicy roomy = new ServerPolicy(false, false, true, true, 4, 81, 9, 8, 24 * HOUR, Set.of(Permission.ENTER, Permission.BUILD));
        AccessControl.invite(archive, owner, timed, Invitation.Kind.TIMED, HOUR, Set.of(Permission.ENTER), 0, roomy);
        AccessControl.invite(archive, owner, single, Invitation.Kind.SINGLE_VISIT, 0, Set.of(Permission.ENTER), 0, roomy);
        long later = 2 * HOUR;

        assertNull(AccessControl.entryRefusal(archive, owner, later, roomy));
        assertNull(AccessControl.entryRefusal(archive, timed, 1, roomy));
        assertEquals(AccessControl.EntryRefusal.NOT_INVITED, AccessControl.entryRefusal(archive, stranger, 0, roomy));
        assertEquals(AccessControl.EntryRefusal.EXPIRED, AccessControl.entryRefusal(archive, timed, later, roomy));

        AccessControl.enter(archive, single, HOME, 1, roomy);
        AccessControl.exit(archive, single);
        assertEquals(AccessControl.EntryRefusal.USED, AccessControl.entryRefusal(archive, single, 2, roomy));

        var keys = Set.of(AccessControl.EntryRefusal.NOT_INVITED.key(), AccessControl.EntryRefusal.EXPIRED.key(),
            AccessControl.EntryRefusal.USED.key(), AccessControl.EntryRefusal.DISABLED.key());
        assertEquals(4, keys.size(), "each reason has its own wording");

        var thrown = assertThrows(ArchiveSecurityException.class, () -> AccessControl.enter(archive, timed, HOME, later, roomy));
        assertEquals(AccessControl.EntryRefusal.EXPIRED.key(), thrown.key());
        thrown = assertThrows(ArchiveSecurityException.class, () -> AccessControl.enter(archive, stranger, HOME, 0, roomy));
        assertEquals(AccessControl.EntryRefusal.NOT_INVITED.key(), thrown.key());
    }

    @Test void layoutRefusalsCarryKeys() {
        var preview = LayoutPlanner.previewExpansion(archive, owner, 0);
        LayoutPlanner.commitExpansion(archive, owner, preview);
        LayoutPlanner.commitExpansion(archive, owner, LayoutPlanner.previewExpansion(archive, owner, 0));
        LayoutPlanner.commitExpansion(archive, owner, LayoutPlanner.previewExpansion(archive, owner, 0)); // the starter room's Grand Hall
        assertEquals("error.nullspace.room.max_size", assertThrows(ArchiveException.class, () -> LayoutPlanner.previewExpansion(archive, owner, 0)).key());
        assertEquals("error.nullspace.room.stale_preview", assertThrows(ArchiveException.class, () -> LayoutPlanner.commitExpansion(archive, owner, preview)).key());
        var limit = assertThrows(ArchiveException.class, () -> LayoutPlanner.previewAnnex(archive, owner, 0, Direction.NORTH, 1));
        assertEquals("error.nullspace.room.limit", limit.key());
        assertArrayEquals(new Object[] {1}, limit.args());
    }

    @Test void presetRefusalsCarryKeys() {
        assertEquals("error.nullspace.preset.bad_name", assertThrows(ArchiveException.class, () -> archive.savePreset(owner, "bad/name", Set.of(Permission.ENTER))).key());
    }
}
