package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AnchorAndExpiryTest {
    private static final ReturnLocation HOME = new ReturnLocation("minecraft:overworld", 10, 70, -5, 90, 0);
    private static final long HOUR = 3_600_000L;
    private final ArchiveRegistry registry = new ArchiveRegistry();

    private ServerPolicy policy(boolean anchors, int maxArchives, int maxChunks) {
        return new ServerPolicy(false, false, true, anchors, maxArchives, maxChunks, 9, 32, 720 * HOUR,
            Set.of(Permission.ENTER, Permission.BUILD, Permission.STORAGE));
    }

    private Archive anchored() {
        Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic");
        a.markInitialized(); a.installAnchor(a.owner());
        return a;
    }

    @Test void maximumActiveAnchorsBoundsTicketsAndPicksLowestAllocationsFirst() {
        Archive first = anchored(), second = anchored(), third = anchored();
        Set<UUID> owners = TicketPlanner.desired(registry.archives().values(), Set.of(), policy(true, 2, 81)).stream()
            .map(TicketPlanner.Ticket::owner).collect(Collectors.toSet());
        assertEquals(Set.of(first.owner(), second.owner()), owners);
        assertFalse(owners.contains(third.owner()));
        assertTrue(TicketPlanner.desired(registry.archives().values(), Set.of(), policy(true, 0, 81)).isEmpty());
    }

    @Test void anAnchorIsOnlyGrantedWhenItWouldGetTickets() {
        for (int i = 0; i < 8; i++) anchored();
        Archive ninth = registry.claim(UUID.randomUUID(), "nullspace:classic");
        ninth.markInitialized();
        assertEquals(TicketPlanner.Grant.NO_SLOT, TicketPlanner.grantFor(registry.archives().values(), ninth, policy(true, 8, 81)));
        assertEquals(TicketPlanner.Grant.GRANTED, TicketPlanner.grantFor(registry.archives().values(), ninth, policy(true, 9, 81)));
        assertEquals(TicketPlanner.Grant.NO_SLOT, TicketPlanner.grantFor(registry.archives().values(), ninth, policy(false, 9, 81)));
        assertEquals(TicketPlanner.Grant.NO_SLOT, TicketPlanner.grantFor(registry.archives().values(), ninth, policy(true, 0, 81)));
        ninth.installAnchor(ninth.owner());
        var owners = TicketPlanner.desired(registry.archives().values(), Set.of(), policy(true, 9, 81)).stream().map(TicketPlanner.Ticket::owner).collect(Collectors.toSet());
        assertTrue(owners.contains(ninth.owner()), "a granted anchor really holds tickets");
    }

    @Test void anArchiveOverTheChunkBudgetIsTooLargeAndAnOverBudgetNeighbourTakesNoSlot() {
        Archive big = registry.claim(UUID.randomUUID(), "nullspace:classic");
        big.markInitialized();
        for (Direction d : new Direction[] {Direction.EAST, Direction.SOUTH, Direction.WEST})
            LayoutPlanner.commitAnnex(big, big.owner(), LayoutPlanner.previewAnnex(big, big.owner(), 0, d, 9), 9);
        int limit = TicketPlanner.roomChunks(big).size() - 1;
        assertEquals(TicketPlanner.Grant.TOO_LARGE, TicketPlanner.grantFor(registry.archives().values(), big, policy(true, 8, limit)));
        big.installAnchor(big.owner());
        Archive small = registry.claim(UUID.randomUUID(), "nullspace:classic");
        small.markInitialized();
        assertEquals(TicketPlanner.Grant.GRANTED, TicketPlanner.grantFor(registry.archives().values(), small, policy(true, 1, limit)), "the dead anchor holds no slot");
    }

    @Test void overBudgetArchiveGetsNothingAndDoesNotConsumeASlot() {
        Archive big = anchored();
        for (Direction d : new Direction[] {Direction.EAST, Direction.SOUTH, Direction.WEST})
            LayoutPlanner.commitAnnex(big, big.owner(), LayoutPlanner.previewAnnex(big, big.owner(), 0, d, 9), 9);
        Archive small = anchored();
        int bigChunks = TicketPlanner.roomChunks(big).size(), smallChunks = TicketPlanner.roomChunks(small).size();
        assertTrue(bigChunks > smallChunks);
        var tickets = TicketPlanner.desired(registry.archives().values(), Set.of(), policy(true, 1, bigChunks - 1));
        assertEquals(smallChunks, tickets.size());
        assertTrue(tickets.stream().allMatch(t -> t.owner().equals(small.owner())), "no partial tickets for the over-budget archive");
        assertEquals(bigChunks + smallChunks, TicketPlanner.desired(registry.archives().values(), Set.of(), policy(true, 8, 81)).size());
    }

    @Test void ticketsAreExactlyTheRoomChunksAndNeverDuplicated() {
        Archive a = anchored();
        var tickets = TicketPlanner.desired(registry.archives().values(), Set.of(), policy(true, 8, 81));
        assertEquals(TicketPlanner.roomChunks(a), tickets.stream().map(TicketPlanner.Ticket::chunk).collect(Collectors.toSet()));
        // reconciling an unchanged plan is a no-op: nothing acquired twice, nothing released.
        var again = TicketPlanner.transition(tickets, TicketPlanner.desired(registry.archives().values(), Set.of(), policy(true, 8, 81)));
        assertTrue(again.acquire().isEmpty());
        assertTrue(again.release().isEmpty());
        // disabling then re-enabling returns the same set.
        var off = TicketPlanner.transition(tickets, TicketPlanner.desired(registry.archives().values(), Set.of(), policy(false, 8, 81)));
        assertEquals(tickets, off.release());
        assertTrue(off.desired().isEmpty());
        var on = TicketPlanner.transition(Set.of(), TicketPlanner.desired(registry.archives().values(), Set.of(), policy(true, 8, 81)));
        assertEquals(tickets, on.acquire());
    }

    @Test void occupiedArchiveHoldsNoAnchorTicketsAndGetsThemBackWhenEmpty() {
        Archive a = anchored(), b = anchored();
        var empty = TicketPlanner.desired(registry.archives().values(), Set.of(), policy(true, 8, 81));
        var occupied = TicketPlanner.desired(registry.archives().values(), Set.of(a.owner()), policy(true, 8, 81));
        assertTrue(occupied.stream().noneMatch(t -> t.owner().equals(a.owner())));
        assertTrue(occupied.stream().anyMatch(t -> t.owner().equals(b.owner())));
        var leave = TicketPlanner.transition(occupied, empty);
        assertTrue(leave.release().isEmpty());
        assertEquals(TicketPlanner.roomChunks(a).size(), leave.acquire().size());
    }

    @Test void uninitializedOrAnchorlessArchivesNeverGetTickets() {
        Archive plain = registry.claim(UUID.randomUUID(), "nullspace:classic");
        plain.markInitialized();
        Archive unfinished = registry.claim(UUID.randomUUID(), "nullspace:classic");
        unfinished.installAnchor(unfinished.owner());
        assertTrue(TicketPlanner.desired(registry.archives().values(), Set.of(), policy(true, 8, 81)).isEmpty());
    }

    @Test void timedInvitationExpiringWhileInsideEndsEveryRightAndBlocksReentry() {
        Archive a = anchored(); UUID guest = UUID.randomUUID(); ServerPolicy p = policy(true, 8, 81);
        AccessControl.invite(a, a.owner(), guest, Invitation.Kind.TIMED, HOUR, Set.of(Permission.ENTER, Permission.BUILD), 1_000, p);
        AccessControl.enter(a, guest, HOME, 2_000, p);
        long later = 1_000 + 2 * HOUR;
        assertTrue(AccessControl.can(a, guest, Permission.BUILD, 2_000, p));
        assertFalse(AccessControl.mayEnter(a, guest, later, p));
        assertFalse(AccessControl.can(a, guest, Permission.BUILD, later, p), "the rights end with the invitation");
        assertFalse(AccessControl.can(a, guest, Permission.ENTER, later, p));
        assertEquals(HOME, AccessControl.exit(a, guest).orElseThrow());
        assertFalse(AccessControl.can(a, guest, Permission.BUILD, 2_000, p));
        assertFalse(AccessControl.mayEnter(a, guest, later, p));
        assertThrows(SecurityException.class, () -> AccessControl.enter(a, guest, HOME, later, p));
        // the owner can still remove an expired guest, offline or not.
        assertTrue(a.invitations().containsKey(guest));
        AccessControl.remove(a, a.owner(), guest);
        assertFalse(a.invitations().containsKey(guest));
    }

    @Test void singleVisitIsConsumedOnCrossingNotOnChecksAndRefusesASecondCrossing() {
        Archive a = anchored(); UUID guest = UUID.randomUUID(); ServerPolicy p = policy(true, 8, 81);
        AccessControl.invite(a, a.owner(), guest, Invitation.Kind.SINGLE_VISIT, 0, Set.of(Permission.ENTER), 0, p);
        for (int i = 0; i < 3; i++) assertTrue(AccessControl.mayEnter(a, guest, 10, p), "opening a gateway only checks");
        assertFalse(a.invitations().get(guest).consumed());
        AccessControl.enter(a, guest, HOME, 10, p);
        assertTrue(a.invitations().get(guest).consumed());
        assertTrue(a.activeVisits().containsKey(guest));
        AccessControl.enter(a, guest, new ReturnLocation("minecraft:the_nether", 1, 2, 3, 0, 0), 11, p);
        assertEquals(HOME, a.activeVisits().get(guest).returnLocation(), "a reconnect never replaces the entry point");
        AccessControl.exit(a, guest);
        assertFalse(AccessControl.mayEnter(a, guest, 12, p));
        assertThrows(SecurityException.class, () -> AccessControl.enter(a, guest, HOME, 12, p));
    }
}
