package dev.nullspace.core;

import java.util.Set;

// part items of other mods (an ae2 cable bus part and the like) that attach to an existing block entity or form a bus
// of their own, so no block item reaches the placement events. matched by type name, no dependency on the mod. parts
// are automation, so they are handled like it
public final class ModParts {
    private ModParts() {}

    public enum Kind { NONE, FACADE, PART }

    static final Set<String> PART_TYPES = Set.of("appeng.api.parts.IPartItem");
    static final Set<String> FACADE_TYPES = Set.of("appeng.api.implementations.items.IFacadeItem");

    private static final ClassValue<Kind> CACHE = new ClassValue<>() {
        @Override protected Kind computeValue(Class<?> type) { return kind(type, PART_TYPES, FACADE_TYPES); }
    };

    public static Kind kind(Class<?> itemClass) { return CACHE.get(itemClass); }

    static Kind kind(Class<?> type, Set<String> parts, Set<String> facades) {
        if (matches(type, parts)) return Kind.PART;
        return matches(type, facades) ? Kind.FACADE : Kind.NONE;
    }

    static boolean matches(Class<?> type, Set<String> names) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (names.contains(c.getName())) return true;
            for (Class<?> i : c.getInterfaces()) if (matches(i, names)) return true;
        }
        return false;
    }
}
