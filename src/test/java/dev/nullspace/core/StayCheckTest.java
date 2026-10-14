package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StayCheckTest {
    private static final ReturnLocation HOME = new ReturnLocation("minecraft:overworld", 10, 70, -5, 90, 0);
    private final ArchiveRegistry registry = new ArchiveRegistry();
    private final ServerPolicy policy = ServerPolicy.DEFAULT;
    private final Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic"), b = registry.claim(UUID.randomUUID(), "nullspace:classic");

    @Test void ownersGuestsWithAVisitAndCardVisitorsStay() {
        UUID guest = UUID.randomUUID(), carded = UUID.randomUUID(), token = UUID.randomUUID();
        AccessControl.invite(a, a.owner(), guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, policy);
        AccessControl.enter(a, guest, HOME, 1, policy);
        AccessControl.printCard(a, a.owner(), token, CardGrant.Kind.UNTIL_REVOKED, 0, Set.of(Permission.ENTER), false, 0, policy);
        AccessControl.enter(a, carded, "Carded", HOME, 1, policy, token);
        assertTrue(AccessControl.mayStayAt(a, a.owner(), 1, policy));
        assertTrue(AccessControl.mayStayAt(a, guest, 1, policy));
        assertTrue(AccessControl.mayStayAt(a, carded, 1, policy));
    }

    @Test void someoneWithNoVisitHereIsAStrangerEvenWithRightsElsewhere() {
        UUID guest = UUID.randomUUID();
        AccessControl.invite(b, b.owner(), guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, policy);
        AccessControl.enter(b, guest, HOME, 1, policy);
        assertFalse(AccessControl.mayStayAt(a, guest, 1, policy), "a visit in B is no place in A");
        assertFalse(AccessControl.mayStayAt(a, b.owner(), 1, policy), "owning B is no place in A");
        assertFalse(AccessControl.mayStayAt(a, UUID.randomUUID(), 1, policy));
    }

    @Test void aGuestWhoWasRemovedOrLeftIsAStrangerAtOnce() {
        UUID guest = UUID.randomUUID();
        AccessControl.invite(a, a.owner(), guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, policy);
        AccessControl.enter(a, guest, HOME, 1, policy);
        AccessControl.remove(a, a.owner(), guest);
        assertFalse(AccessControl.mayStayAt(a, guest, 1, policy));
    }

    @Test void standingOutsideEveryArchiveIsNobodysBusiness() {
        assertTrue(AccessControl.mayStayAt(null, UUID.randomUUID(), 1, policy));
    }
}
