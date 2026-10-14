package dev.nullspace.core;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record Destination(int id, String name, String dimension, double x, double y, double z, float yaw, String icon) {
    public static final int MAX_NAME = 32;
    public static final int LABEL_CHARS = 24;
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} _.'()+\\-]{1," + MAX_NAME + "}");
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    public static final int MAX_Y = 4064, MIN_Y = -2048;

    public Destination {
        Objects.requireNonNull(name, "name"); Objects.requireNonNull(dimension, "dimension"); Objects.requireNonNull(icon, "icon");
        if (id < 0 || !validName(name) || !validPlace(dimension, x, y, z, yaw) || !validIcon(icon)) throw ArchiveException.corrupt("destination");
    }

    public static boolean validName(String name) { return name != null && name.equals(name.strip()) && NAME.matcher(name).matches(); }
    public static boolean validIcon(String icon) { return icon != null && (icon.isEmpty() || RESOURCE.matcher(icon).matches()); }
    public static boolean validPlace(String dimension, double x, double y, double z, float yaw) {
        return dimension != null && RESOURCE.matcher(dimension).matches() && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Float.isFinite(yaw)
            && Math.abs(x) <= Allocation.WORLD_LIMIT && Math.abs(z) <= Allocation.WORLD_LIMIT && y >= MIN_Y && y <= MAX_Y;
    }

    // player input: refuses with a message key instead of a corruption error. the name is trimmed first
    public static Destination create(int id, String name, String dimension, double x, double y, double z, float yaw, String icon) {
        String clean = name == null ? "" : name.strip();
        if (!validName(clean)) throw new ArchiveException("error.nullspace.destination.bad_name", MAX_NAME);
        if (!validPlace(dimension, x, y, z, yaw)) throw new ArchiveException("error.nullspace.destination.bad_place");
        if (!validIcon(icon)) throw new ArchiveException("error.nullspace.destination.bad_icon");
        return new Destination(id, clean, dimension, x, y, z, yaw, icon);
    }

    public Destination withName(String newName) { return create(id, newName, dimension, x, y, z, yaw, icon); }
    public Destination withIcon(String newIcon) { return create(id, name, dimension, x, y, z, yaw, newIcon); }

    public String label() { return clip(name, LABEL_CHARS); }

    public static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    public boolean sameName(String other) { return name.toLowerCase(Locale.ROOT).equals(other.strip().toLowerCase(Locale.ROOT)); }
}
