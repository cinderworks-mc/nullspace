package dev.nullspace.core;

// square spiral, 8192 blocks between centres. ids are not recycled
public record Allocation(long id, int originX, int originZ) {
    public static final int SPACING = 8192;
    public static final int BUILD_RADIUS = 1024;
    public static final int WORLD_LIMIT = 29_999_984;
    public static final long MAX_RING = (WORLD_LIMIT - BUILD_RADIUS) / SPACING;
    public static final long LAST_ID = (2 * MAX_RING + 1) * (2 * MAX_RING + 1) - 1;

    public Allocation {
        if (id < 0 || Math.abs((long) originX) + BUILD_RADIUS > WORLD_LIMIT
            || Math.abs((long) originZ) + BUILD_RADIUS > WORLD_LIMIT) {
            throw new ArchiveException("error.nullspace.allocation.exhausted");
        }
    }
    public static Allocation at(long id) {
        if (id < 0 || id > LAST_ID) throw new ArchiveException("error.nullspace.allocation.exhausted");
        if (id == 0) return new Allocation(0, 0, 0);
        long ring = (long) Math.ceil((Math.sqrt(id + 1d) - 1) / 2);
        long edge = 2 * ring;
        long corner = (2 * ring + 1) * (2 * ring + 1) - 1;
        long x, z;
        if (id >= corner - edge) { x = ring - (corner - id); z = -ring; }
        else if (id >= corner - 2 * edge) { x = -ring; z = -ring + (corner - edge - id); }
        else if (id >= corner - 3 * edge) { x = -ring + (corner - 2 * edge - id); z = ring; }
        else { x = ring; z = ring - (corner - 3 * edge - id); }
        return new Allocation(id, Math.toIntExact(x * SPACING), Math.toIntExact(z * SPACING));
    }
    public boolean contains(int x, int z) {
        return Math.abs((long) x - originX) <= BUILD_RADIUS && Math.abs((long) z - originZ) <= BUILD_RADIUS;
    }
}
