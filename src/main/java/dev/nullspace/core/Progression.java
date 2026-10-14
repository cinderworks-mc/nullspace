package dev.nullspace.core;

import java.util.List;

public final class Progression {
    private Progression() {}

    public record Extra(String itemId, int count) {
        public Extra {
            if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || count < 1) throw ArchiveException.corrupt("negative-cost");
        }
    }

    public record Cost(int modules, int diamonds, int netherite, List<Extra> extras) {
        public Cost {
            if (modules < 0 || diamonds < 0 || netherite < 0) throw ArchiveException.corrupt("negative-cost");
            extras = List.copyOf(extras);
        }
        public Cost(int modules, int diamonds, int netherite) { this(modules, diamonds, netherite, List.of()); }
    }

    public static final String CHRONON_CELL = "tempad:chronon_cell", TIME_STEEL = "tempad:time_steel";

    // 9 to 13 costs firstModules expanders and four diamonds; 13 to 17 costs secondModules and one netherite ingot.
    // the grand hall step has its own method
    public static Cost expansion(int currentTier, int firstModules, int secondModules) {
        return switch (currentTier) {
            case 0 -> new Cost(firstModules, 4, 0);
            case 1 -> new Cost(secondModules, 0, 1);
            default -> throw new ArchiveException("error.nullspace.room.max_size");
        };
    }

    public static Cost ceiling(int currentHeightTier, int firstModules, int secondModules, boolean tempadParts) {
        List<Extra> extras = tempadParts ? List.of(new Extra(CHRONON_CELL, 1)) : List.of();
        return switch (currentHeightTier) {
            case 0 -> new Cost(firstModules, 2, 0, extras);
            case 1 -> new Cost(secondModules, 4, 0, extras);
            default -> throw new ArchiveException("error.nullspace.room.max_height");
        };
    }

    public static Cost hall(int modules, int netherite, boolean tempadParts) {
        return new Cost(modules, 0, netherite, tempadParts ? List.of(new Extra(TIME_STEEL, 2)) : List.of());
    }

    public static Cost annex(int existingRooms, int base, int step) {
        if (existingRooms < 1) throw ArchiveException.corrupt("no-starter-room");
        return new Cost(base + step * (existingRooms - 1), existingRooms, 0);
    }
}
