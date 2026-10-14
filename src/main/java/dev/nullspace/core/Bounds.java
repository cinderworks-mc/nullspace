package dev.nullspace.core;

public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public Bounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) throw ArchiveException.corrupt("bounds");
    }
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
    public boolean intersects(Bounds other) {
        return minX <= other.maxX && maxX >= other.minX && minY <= other.maxY && maxY >= other.minY
            && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
