package dev.nullspace.core;

import java.util.EnumSet;
import java.util.Set;

// what the terminal repaints in one go: every room at once, or the tunnels. walls are all four sides together
public enum SurfaceGroup {
    FLOOR(EnumSet.of(Surface.FLOOR)), CEILING(EnumSet.of(Surface.CEILING)), WALLS(EnumSet.of(Surface.NORTH, Surface.EAST, Surface.SOUTH, Surface.WEST));

    private final Set<Surface> surfaces;
    SurfaceGroup(Set<Surface> surfaces) { this.surfaces = surfaces; }
    public Set<Surface> surfaces() { return EnumSet.copyOf(surfaces); }
}
