package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GuestLookupTest {
    private static final UUID GHOST = UUID.randomUUID(), ALEX = UUID.randomUUID(), STRANGER = UUID.randomUUID();
    private static final Map<UUID, String> NAMES = Map.of(GHOST, GHOST.toString(), ALEX, "Alex");

    @Test void aUuidOnlyGuestResolvesByItsUuidText() {
        assertEquals(GHOST, GuestLookup.invited(NAMES, GHOST.toString()).orElseThrow());
        assertEquals(GHOST, GuestLookup.invited(NAMES, "  " + GHOST.toString().toUpperCase() + " ").orElseThrow());
    }

    @Test void aKnownGuestResolvesByNameIgnoringCase() {
        assertEquals(ALEX, GuestLookup.invited(NAMES, "aLeX").orElseThrow());
        assertEquals(ALEX, GuestLookup.invited(NAMES, ALEX.toString()).orElseThrow());
    }

    @Test void aUuidThatWasNeverInvitedDoesNotResolve() {
        assertTrue(GuestLookup.invited(NAMES, STRANGER.toString()).isEmpty());
        assertTrue(GuestLookup.invited(NAMES, "Nobody").isEmpty());
        assertTrue(GuestLookup.invited(NAMES, " ").isEmpty());
    }

    @Test void twoGuestsWithTheSameNameResolveToNeitherByName() {
        UUID other = UUID.randomUUID();
        var clash = Map.of(ALEX, "Alex", other, "alex");
        assertTrue(GuestLookup.invited(clash, "ALEX").isEmpty());
        assertEquals(other, GuestLookup.invited(clash, other.toString()).orElseThrow());
    }
}
