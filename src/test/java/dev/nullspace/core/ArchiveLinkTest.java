package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArchiveLinkTest {
    private final UUID me = UUID.randomUUID(), other = UUID.randomUUID();

    @Test void aFullTempadWithoutTheUpgradeHidesOnlyTheViewersOwnArchive() {
        assertFalse(ArchiveLink.lists(true, true, false, me, me), "own archive needs the upgrade");
        assertTrue(ArchiveLink.lists(true, true, false, other, me), "an archive the player was invited to is not this rule's business");
    }

    @Test void theUpgradeListsTheOwnArchive() {
        assertTrue(ArchiveLink.lists(true, true, true, me, me));
        assertTrue(ArchiveLink.lists(true, true, true, other, me), "the upgrade never hides anything");
    }

    @Test void theServerSwitchOffRestoresTheOldBehaviourAndMakesTheUpgradeInert() {
        for (boolean installed : new boolean[] {false, true}) {
            assertTrue(ArchiveLink.lists(false, true, installed, me, me), "switch off, installed=" + installed + ": the full Tempad lists the own archive as it always did");
            assertTrue(ArchiveLink.lists(false, true, installed, other, me));
        }
    }

    @Test void aDeviceThatCannotTakeUpgradesIsNeverFiltered() {
        // the Timedoor Projector (linked by the Access Cartridge) resolves its archive through the same provider and must keep working
        for (boolean installed : new boolean[] {false, true}) assertTrue(ArchiveLink.lists(true, false, installed, me, me));
    }

    @Test void unknownIdsFailClosedOnlyWhereTheGateApplies() {
        assertFalse(ArchiveLink.lists(true, true, false, null, me));
        assertFalse(ArchiveLink.lists(true, true, false, me, null));
        assertFalse(ArchiveLink.lists(true, true, false, null, null));
        assertTrue(ArchiveLink.lists(true, true, true, null, null), "installed: nothing to hide");
        assertTrue(ArchiveLink.lists(false, true, false, null, null), "switch off: nothing to hide");
        assertTrue(ArchiveLink.lists(true, false, false, null, null), "projector: nothing to hide");
    }

    @Test void theWholeTruthTable() {
        // required, upgradable, installed, own archive -> listed
        boolean[] b = {false, true};
        for (boolean required : b) for (boolean upgradable : b) for (boolean installed : b) {
            boolean expectedOwn = !(required && upgradable && !installed);
            assertEquals(expectedOwn, ArchiveLink.lists(required, upgradable, installed, me, me), "own: " + required + "/" + upgradable + "/" + installed);
            assertTrue(ArchiveLink.lists(required, upgradable, installed, other, me), "other: " + required + "/" + upgradable + "/" + installed);
        }
    }

    @Test void theUpgradeIdIsTheOneTheWorkstationRecipeInstalls() {
        assertEquals("nullspace", ArchiveLink.UPGRADE_NAMESPACE);
        assertEquals("archive_link", ArchiveLink.UPGRADE_PATH);
    }
}
