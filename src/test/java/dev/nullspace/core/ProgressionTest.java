package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProgressionTest {
    @Test void expansionEscalatesToNetheriteOnlyAtTheLastTier() {
        assertEquals(new Progression.Cost(1, 4, 0), Progression.expansion(0, 1, 2));
        assertEquals(new Progression.Cost(2, 0, 1), Progression.expansion(1, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> Progression.expansion(2, 1, 2));
    }

    @Test void annexCostsGrowWithRoomCountAndNeverNeedNetherite() {
        Progression.Cost previous = Progression.annex(1, 1, 1);
        assertEquals(new Progression.Cost(1, 1, 0), previous);
        for (int rooms = 2; rooms <= 8; rooms++) {
            Progression.Cost next = Progression.annex(rooms, 1, 1);
            assertTrue(next.modules() > previous.modules() && next.diamonds() > previous.diamonds());
            assertEquals(0, next.netherite());
            previous = next;
        }
        assertEquals(new Progression.Cost(8, 8, 0), previous);
    }
}
