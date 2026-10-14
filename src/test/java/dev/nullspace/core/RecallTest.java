package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecallTest {
    private static final ReturnLocation SPOT = new ReturnLocation("minecraft:overworld", 100.5, 64, -20.5, 90, 0);
    private static final long MIN = 60_000L, T0 = 1_000_000L;
    private final ArchiveRegistry registry = new ArchiveRegistry();

    private Archive archive(boolean installed) {
        Archive a = registry.claim(UUID.randomUUID(), "nullspace:classic");
        a.markInitialized();
        if (installed) a.installRecall(a.owner());
        return a;
    }

    private static Archive died(Archive a) {
        assertTrue(Recall.recordDeath(a, a.owner(), false, SPOT, T0, true));
        return a;
    }

    @Test void clampKeepsTheWindowBetweenOneMinuteAndOneDay() {
        assertEquals(1, Recall.clampMinutes(-5));
        assertEquals(1, Recall.clampMinutes(0));
        assertEquals(60, Recall.clampMinutes(60));
        assertEquals(1440, Recall.clampMinutes(100_000));
        assertEquals(1440, new ServerPolicy(false, false, true, true, 8, 81, 9, 32, 1, Set.of(Permission.ENTER), true, 99_999).recallMinutes());
        assertTrue(ServerPolicy.DEFAULT.allowRecall());
        assertEquals(60, ServerPolicy.DEFAULT.recallMinutes());
    }

    @Test void onlyTheOwnerOfAnArchiveWithTheModuleRecordsADeath() {
        Archive plain = archive(false), fitted = archive(true);
        assertFalse(Recall.recordDeath(plain, plain.owner(), false, SPOT, T0, true), "no module, nothing recorded");
        assertNull(plain.recallPoint());
        assertFalse(Recall.recordDeath(fitted, UUID.randomUUID(), false, SPOT, T0, true), "a guest (or anyone else) never records");
        assertNull(fitted.recallPoint());
        assertFalse(Recall.recordDeath(fitted, fitted.owner(), false, SPOT, T0, false), "server veto: nothing recorded");
        assertNull(fitted.recallPoint());
        assertFalse(Recall.recordDeath(fitted, fitted.owner(), true, SPOT, T0, true), "dying inside an archive records nothing");
        assertNull(fitted.recallPoint());
        assertTrue(Recall.recordDeath(fitted, fitted.owner(), false, SPOT, T0, true));
        assertFalse(fitted.recallPoint().armed(), "a death alone is not yet usable");
    }

    @Test void dyingInsideAnArchiveLeavesAnExistingPointAlone() {
        Archive a = died(archive(true));
        assertFalse(Recall.recordDeath(a, a.owner(), true, new ReturnLocation("nullspace:archive", 1, 65, 1, 0, 0), T0 + MIN, true));
        assertEquals(SPOT, a.recallPoint().where());
    }

    @Test void aPointIsOnlyUsableAfterRespawningInTheOwnArchive() {
        Archive a = died(archive(true));
        assertTrue(Recall.target(a, a.owner(), T0, 60, true).isEmpty(), "unarmed: no target yet");
        assertTrue(Recall.armOnRespawn(a, a.owner(), true, T0 + MIN, 60, true));
        assertEquals(SPOT, Recall.target(a, a.owner(), T0 + 2 * MIN, 60, true).orElseThrow());
        Archive b = died(archive(true));
        assertFalse(Recall.armOnRespawn(b, b.owner(), false, T0 + MIN, 60, true), "respawning elsewhere drops it");
        assertNull(b.recallPoint());
    }

    @Test void guestsNeverGetATarget() {
        Archive a = died(archive(true));
        Recall.armOnRespawn(a, a.owner(), true, T0, 60, true);
        assertTrue(Recall.target(a, UUID.randomUUID(), T0, 60, true).isEmpty());
        assertFalse(Recall.armOnRespawn(a, UUID.randomUUID(), true, T0, 60, true));
        assertFalse(Recall.clear(a, UUID.randomUUID()), "a guest crossing cannot spend the owner's point");
        assertNotNull(a.recallPoint());
    }

    @Test void expiryIsExactAndConfigurable() {
        Archive a = died(archive(true));
        Recall.armOnRespawn(a, a.owner(), true, T0, 60, true);
        assertTrue(Recall.target(a, a.owner(), T0 + 60 * MIN - 1, 60, true).isPresent());
        assertTrue(Recall.target(a, a.owner(), T0 + 60 * MIN, 60, true).isEmpty(), "expired at exactly the window");
        assertTrue(Recall.target(a, a.owner(), T0 + 5 * MIN, 5, true).isEmpty(), "a shorter configured window applies to an existing point");
        assertTrue(Recall.target(a, a.owner(), T0 + 5 * MIN, 1440, true).isPresent(), "a longer one too");
        assertTrue(Recall.target(a, a.owner(), T0 - 1, 60, true).isEmpty(), "a clock that went backwards never extends a point");
        assertTrue(Recall.dropExpired(a, T0 + 61 * MIN, 60));
        assertNull(a.recallPoint());
        assertFalse(Recall.dropExpired(a, T0 + 61 * MIN, 60));
    }

    @Test void anExpiredPointIsNotArmedByARespawn() {
        Archive a = died(archive(true));
        assertFalse(Recall.armOnRespawn(a, a.owner(), true, T0 + 61 * MIN, 60, true));
        assertNull(a.recallPoint());
    }

    @Test void singleUse() {
        Archive a = died(archive(true));
        Recall.armOnRespawn(a, a.owner(), true, T0, 60, true);
        assertTrue(Recall.target(a, a.owner(), T0, 60, true).isPresent());
        assertTrue(Recall.target(a, a.owner(), T0, 60, true).isPresent(), "peeking never spends it (an exit that is never walked into loses nothing)");
        assertTrue(Recall.clear(a, a.owner()), "leaving the archive spends it");
        assertTrue(Recall.target(a, a.owner(), T0, 60, true).isEmpty());
        assertFalse(Recall.clear(a, a.owner()));
    }

    @Test void serverVetoAndMissingModuleDisableAnExistingPoint() {
        Archive a = died(archive(true));
        Recall.armOnRespawn(a, a.owner(), true, T0, 60, true);
        assertTrue(Recall.target(a, a.owner(), T0, 60, false).isEmpty(), "veto at runtime");
        assertTrue(Recall.target(a, a.owner(), T0, 60, true).isPresent(), "and it comes back when the veto is lifted");
        Archive b = archive(false);
        b.restoreRecall(false, new RecallPoint(SPOT, T0, true));
        assertNull(b.recallPoint(), "no module, no point, even from a hand-edited save");
    }

    @Test void respawningAgainInsideKeepsTheSamePoint() {
        Archive a = died(archive(true));
        Recall.armOnRespawn(a, a.owner(), true, T0, 60, true);
        assertTrue(Recall.armOnRespawn(a, a.owner(), true, T0 + 10 * MIN, 60, true));
        assertEquals(T0, a.recallPoint().diedAtMillis(), "dying inside does not renew the window");
        assertFalse(Recall.armOnRespawn(a, a.owner(), false, T0 + 11 * MIN, 60, true), "but respawning outside drops it");
        assertNull(a.recallPoint());
    }

    @Test void onlyTheOwnerInstallsTheModule() {
        Archive a = archive(false);
        assertThrows(ArchiveSecurityException.class, () -> a.installRecall(UUID.randomUUID()));
        assertFalse(a.recallInstalled());
        a.installRecall(a.owner());
        assertTrue(a.recallInstalled());
    }

    @Test void anExitOpenedBeforeTheDeathSpotWasArmedIsStale() {
        // the overworld spawn the gateway was opened with versus the death spot the exit leads to now (the case seen in a real run: 178 blocks apart).
        assertFalse(Recall.sameSpot("minecraft:overworld", -143.5, 79, 160.5, "minecraft:overworld", -59.5, 201, 60.5));
        assertFalse(Recall.sameSpot("minecraft:overworld", 10, 70, 10, "minecraft:the_nether", 10, 70, 10));
        assertTrue(Recall.sameSpot("minecraft:overworld", 100.5, 64, -20.5, "minecraft:overworld", 100.5, 64, -20.5));
        assertTrue(Recall.sameSpot("minecraft:overworld", 100.5, 64, -20.5, "minecraft:overworld", 101.5, 64, -20.0));
    }
}
