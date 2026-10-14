package dev.nullspace.core;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class GuestLookup {
    private GuestLookup() {}

    public static Optional<UUID> invited(Map<UUID, String> names, String text) {
        String wanted = text.trim();
        if (wanted.isEmpty()) return Optional.empty();
        try {
            UUID id = UUID.fromString(wanted);
            if (names.containsKey(id)) return Optional.of(id);
        } catch (IllegalArgumentException ignored) { }
        UUID found = null;
        for (var entry : names.entrySet()) {
            if (!entry.getValue().equalsIgnoreCase(wanted)) continue;
            if (found != null) return Optional.empty();
            found = entry.getKey();
        }
        return Optional.ofNullable(found);
    }
}
