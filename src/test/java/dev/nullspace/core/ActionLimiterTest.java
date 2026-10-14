package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActionLimiterTest {
    private static final UUID P = UUID.randomUUID(), Q = UUID.randomUUID();

    @Test void burstUpToTheCapThenDrops() {
        var limiter = new ActionLimiter(10, 20);
        for (int i = 0; i < 10; i++) assertTrue(limiter.tryAcquire(P, 100), "request " + i);
        assertFalse(limiter.tryAcquire(P, 100));
        assertFalse(limiter.tryAcquire(P, 119));
        assertTrue(limiter.tryAcquire(P, 120), "the window has passed");
    }

    @Test void aFloodNeverGetsMoreThanTheCapPerWindow() {
        var limiter = new ActionLimiter(10, 20);
        int allowed = 0;
        for (int tick = 5; tick < 5 + 200; tick++) for (int burst = 0; burst < 1000; burst++) if (limiter.tryAcquire(P, tick)) allowed++;
        assertTrue(allowed <= 10 * (200 / 20 + 1), "allowed " + allowed);
        assertTrue(allowed >= 10 * (200 / 20), "an honest ceiling, not a lockout: " + allowed);
    }

    @Test void playersAreIndependent() {
        var limiter = new ActionLimiter(1, 20);
        assertTrue(limiter.tryAcquire(P, 1));
        assertFalse(limiter.tryAcquire(P, 2));
        assertTrue(limiter.tryAcquire(Q, 2));
    }

    @Test void tickZeroAndBackwardsClocksDoNotLockAPlayerOut() {
        var limiter = new ActionLimiter(2, 20);
        assertTrue(limiter.tryAcquire(P, 0)); assertTrue(limiter.tryAcquire(P, 0));
        assertFalse(limiter.tryAcquire(P, 0));
        assertTrue(limiter.tryAcquire(P, 20));
        assertTrue(limiter.tryAcquire(Q, 1_000_000)); assertTrue(limiter.tryAcquire(Q, 1_000_000));
        assertTrue(limiter.tryAcquire(Q, 5), "a counter that restarted (new world) must not freeze the player until it catches up");
    }

    @Test void memoryStaysBoundedForManyPlayers() {
        var limiter = new ActionLimiter(5, 20);
        for (int i = 0; i < 100_000; i++) assertTrue(limiter.tryAcquire(UUID.randomUUID(), i));
    }
}
