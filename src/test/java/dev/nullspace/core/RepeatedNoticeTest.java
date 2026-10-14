package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepeatedNoticeTest {
    private static final UUID PLAYER = UUID.randomUUID(), OTHER_PLAYER = UUID.randomUUID(), DOOR = UUID.randomUUID(), OTHER_DOOR = UUID.randomUUID();

    @Test void standingInAGatewayIsToldOncePerWindowNotEveryTick() {
        var notice = new RepeatedNotice(100);
        int told = 0;
        for (long tick = 1000; tick < 1000 + 400; tick++) if (notice.mayTell(PLAYER, DOOR, tick)) told++; // 400 ticks = 20 s of standing still
        assertEquals(4, told, "once at the start and then once per 100 ticks, not 400 times");
    }

    @Test void theFirstRefusalIsAlwaysSaid() {
        assertTrue(new RepeatedNotice(100).mayTell(PLAYER, DOOR, 0));
        assertTrue(new RepeatedNotice(100).mayTell(PLAYER, DOOR, 123456789L));
    }

    @Test void theWindowIsHonouredExactly() {
        var notice = new RepeatedNotice(100);
        assertTrue(notice.mayTell(PLAYER, DOOR, 500));
        assertFalse(notice.mayTell(PLAYER, DOOR, 501));
        assertFalse(notice.mayTell(PLAYER, DOOR, 599));
        assertTrue(notice.mayTell(PLAYER, DOOR, 600));
    }

    @Test void anotherGatewayOrAnotherPlayerIsNotSilencedByThisOne() {
        var notice = new RepeatedNotice(100);
        assertTrue(notice.mayTell(PLAYER, DOOR, 500));
        assertTrue(notice.mayTell(PLAYER, OTHER_DOOR, 500), "a different gateway is a different refusal");
        assertTrue(notice.mayTell(OTHER_PLAYER, DOOR, 500), "another player must hear it too");
        assertFalse(notice.mayTell(PLAYER, DOOR, 501));
        assertFalse(notice.mayTell(PLAYER, OTHER_DOOR, 501));
        assertFalse(notice.mayTell(OTHER_PLAYER, DOOR, 501));
    }

    @Test void swappingPlayerAndGatewayIsNotTheSameKey() {
        var notice = new RepeatedNotice(100);
        assertTrue(notice.mayTell(PLAYER, DOOR, 500));
        assertTrue(notice.mayTell(DOOR, PLAYER, 500));
    }

    @Test void aClockThatRestartedDoesNotMuteAPlayerUntilItCatchesUp() {
        var notice = new RepeatedNotice(100);
        assertTrue(notice.mayTell(PLAYER, DOOR, 1_000_000));
        assertTrue(notice.mayTell(PLAYER, DOOR, 5), "new world, tick counter back at the start");
    }
}
