package dev.nullspace.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// found by claiming on a server built from the jar: brigadier's unquoted strings stop at a colon, so the chooser
// click ran /nullspace claim nullspace:classic and failed. the id has to be quoted
class ClaimCommandTextTest {
    @Test
    void theChooserCommandQuotesTheNamespacedPresetId() {
        assertEquals("/nullspace claim \"nullspace:classic\"", ClaimCommandText.of("nullspace:classic"));
        assertEquals("/nullspace claim \"mypack:cabin\"", ClaimCommandText.of("mypack:cabin"));
    }
}
