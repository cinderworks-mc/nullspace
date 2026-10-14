package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CarryRulesTest {
    private static final UUID OWNER = UUID.randomUUID(), GUEST = UUID.randomUUID(), OTHER_GUEST = UUID.randomUUID();

    @Test void aGuestCannotCarryTheOwnerWhateverTheyWereGranted() {
        assertTrue(CarryRules.ownerMayNotBeCarried(OWNER, OWNER, GUEST, false));
    }

    @Test void anOperatorMay() {
        assertFalse(CarryRules.ownerMayNotBeCarried(OWNER, OWNER, GUEST, true));
    }

    @Test void anotherGuestIsNotProtectedByThisRule() {
        assertFalse(CarryRules.ownerMayNotBeCarried(OWNER, OTHER_GUEST, GUEST, false), "picking up a visitor is the ordinary entity right (INTERACT_ENTITY), not this rule");
    }

    @Test void theOwnerCannotPickThemselvesUpSoTheRuleDoesNotApply() {
        assertFalse(CarryRules.ownerMayNotBeCarried(OWNER, OWNER, OWNER, false));
    }
}
