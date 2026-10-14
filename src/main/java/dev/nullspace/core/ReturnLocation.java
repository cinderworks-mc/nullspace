package dev.nullspace.core;

import java.util.Objects;

// all fields are saved, dimension included, for each visitor on their own
public record ReturnLocation(String dimension, double x, double y, double z, float yaw, float pitch) {
    public ReturnLocation {
        Objects.requireNonNull(dimension, "dimension");
        if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || !Double.isFinite(x) || !Double.isFinite(y)
            || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
            || Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984) {
            throw ArchiveException.corrupt("return-location");
        }
    }
}
