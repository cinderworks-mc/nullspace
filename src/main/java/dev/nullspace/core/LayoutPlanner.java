package dev.nullspace.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class LayoutPlanner {
    private LayoutPlanner() {}

    public enum Axis { WIDTH, HEIGHT }

    public record ExpansionPlan(UUID owner, int roomId, Axis axis, int oldTier, int oldHeightTier, int newWidth, int newHeight, Bounds oldInterior,
                                Bounds newInterior, Set<BlockPoint> removeShell, Set<BlockPoint> addShell, Set<BlockPoint> restyleShell) {
        public ExpansionPlan { removeShell = Set.copyOf(removeShell); addShell = Set.copyOf(addShell); restyleShell = Set.copyOf(restyleShell); }
    }
    public record AnnexPlan(UUID owner, int sourceId, Direction direction, int roomId, int gridX, int gridZ,
                            Bounds reservedBounds) {}

    public static ExpansionPlan previewExpansion(Archive archive, UUID actor, int roomId) {
        return previewExpansion(archive, actor, roomId, Axis.WIDTH, ServerPolicy.DEFAULT);
    }
    public static ExpansionPlan previewExpansion(Archive archive, UUID actor, int roomId, Axis axis) {
        return previewExpansion(archive, actor, roomId, axis, ServerPolicy.DEFAULT);
    }
    // the server's width and ceiling caps only veto this upgrade; a room that is already wider or taller keeps what
    // it has
    public static ExpansionPlan previewExpansion(Archive archive, UUID actor, int roomId, Axis axis, ServerPolicy policy) {
        archive.requireOwner(actor);
        Room room = archive.room(roomId);
        int width = room.width(), height = room.height();
        if (axis == Axis.WIDTH) {
            width = room.nextWidth();
            if (width < 0) throw new ArchiveException("error.nullspace.room.max_size", room.maxWidth());
            if (width > policy.maxRoomWidth()) throw new ArchiveException("error.nullspace.room.width_vetoed", policy.maxRoomWidth());
        } else {
            height = room.nextHeight();
            if (height < 0) throw new ArchiveException("error.nullspace.room.max_height");
            if (height > policy.maxCeilingHeight()) throw new ArchiveException("error.nullspace.room.height_vetoed", policy.maxCeilingHeight());
        }
        Bounds proposed = room.bounds(archive.allocation(), width, height, true);
        for (Room other : archive.rooms().values()) {
            if (other.id() != roomId && proposed.intersects(other.reservedBounds(archive.allocation()))) {
                throw new ArchiveException("error.nullspace.room.expand_blocked");
            }
        }
        Bounds oldBox = room.shell(archive.allocation());
        Set<BlockPoint> oldShell = shellBlocks(archive, room, room.width(), room.height());
        Set<BlockPoint> newShell = shellBlocks(archive, room, width, height);
        Set<BlockPoint> removed = new HashSet<>(oldShell); removed.removeAll(newShell);
        Set<BlockPoint> added = new HashSet<>(newShell); added.removeAll(oldShell);
        Set<BlockPoint> restyle = new HashSet<>();
        for (BlockPoint p : oldShell) {
            if (newShell.contains(p) && surfaceOf(p.x(), p.y(), p.z(), oldBox) != surfaceOf(p.x(), p.y(), p.z(), proposed)) restyle.add(p);
        }
        // connected tunnels are already valid interiors, so no new shell goes in them
        removed.removeIf(point -> inConnectedPassage(archive, room, point));
        added.removeIf(point -> inConnectedPassage(archive, room, point));
        restyle.removeIf(point -> inConnectedPassage(archive, room, point));
        return new ExpansionPlan(archive.owner(), roomId, axis, room.tier(), room.heightTier(), width, height,
            room.interior(archive.allocation()), room.bounds(archive.allocation(), width, height, false), removed, added, restyle);
    }
    public static void commitExpansion(Archive archive, UUID actor, ExpansionPlan plan) {
        archive.requireOwner(actor);
        Room room = archive.room(plan.roomId());
        if (!archive.owner().equals(plan.owner()) || room.tier() != plan.oldTier() || room.heightTier() != plan.oldHeightTier()
            || (plan.axis() == Axis.WIDTH ? room.nextWidth() != plan.newWidth() : room.nextHeight() != plan.newHeight()))
            throw new ArchiveException("error.nullspace.room.stale_preview");
        if (plan.axis() == Axis.WIDTH) room.enlarge(); else room.raise();
    }
    public static Surface surfaceOf(int x, int y, int z, Bounds shell) {
        if (y == shell.minY()) return Surface.FLOOR;
        if (y == shell.maxY()) return Surface.CEILING;
        if (x == shell.minX()) return Surface.WEST;
        if (x == shell.maxX()) return Surface.EAST;
        if (z == shell.minZ()) return Surface.NORTH;
        return Surface.SOUTH;
    }
    public static AnnexPlan previewAnnex(Archive archive, UUID actor, int sourceId, Direction direction, int maxRooms) {
        archive.requireOwner(actor);
        if (maxRooms < 1 || maxRooms > 256) throw ArchiveException.corrupt("room-limit");
        if (archive.rooms().size() >= maxRooms) throw new ArchiveException("error.nullspace.room.limit", maxRooms);
        Room source = archive.room(sourceId);
        if (source.connections().containsKey(direction)) throw new ArchiveException("error.nullspace.room.doorway_used");
        int x = source.gridX() + direction.dx, z = source.gridZ() + direction.dz;
        int id = archive.rooms().keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        Room proposed = new Room(id, x, z, 0);
        Bounds reserved = proposed.reservedBounds(archive.allocation());
        for (Room other : archive.rooms().values()) {
            if (reserved.intersects(other.reservedBounds(archive.allocation()))) throw new ArchiveException("error.nullspace.room.space_taken");
        }
        return new AnnexPlan(archive.owner(), sourceId, direction, id, x, z, reserved);
    }
    public static Room commitAnnex(Archive archive, UUID actor, AnnexPlan plan, int maxRooms) {
        AnnexPlan current = previewAnnex(archive, actor, plan.sourceId(), plan.direction(), maxRooms);
        if (!current.equals(plan)) throw new ArchiveException("error.nullspace.room.stale_preview");
        Room room = new Room(plan.roomId(), plan.gridX(), plan.gridZ(), 0);
        room.connect(plan.direction().opposite(), plan.sourceId());
        archive.room(plan.sourceId()).connect(plan.direction(), plan.roomId());
        archive.putRoom(room);
        return room;
    }
    // only a leaf can go. the adapter backs up the contents and checks operator permission
    public static Room removeLeafForRecovery(Archive archive, int roomId, boolean backupConfirmed, boolean contentsEmpty) {
        if (!backupConfirmed || !contentsEmpty) throw new ArchiveException("error.nullspace.room.recovery_backup");
        if (roomId == 0) throw new ArchiveException("error.nullspace.room.starter_protected");
        Room room = archive.room(roomId);
        if (room.connections().size() != 1) throw new ArchiveException("error.nullspace.room.leaf_only");
        room.connections().forEach((direction, neighbor) -> archive.room(neighbor).disconnect(direction.opposite()));
        archive.removeRoom(roomId);
        return room;
    }
    public static Set<BlockPoint> shellBlocks(Archive archive, Room room, int width) { return shellBlocks(archive, room, width, room.height()); }
    public static Set<BlockPoint> shellBlocks(Archive archive, Room room, int width, int height) {
        Bounds shell = room.bounds(archive.allocation(), width, height, true);
        Set<BlockPoint> result = new HashSet<>();
        for (int x = shell.minX(); x <= shell.maxX(); x++) {
            for (int y = shell.minY(); y <= shell.maxY(); y++) {
                for (int z = shell.minZ(); z <= shell.maxZ(); z++) {
                    if (x == shell.minX() || x == shell.maxX() || y == shell.minY() || y == shell.maxY() || z == shell.minZ() || z == shell.maxZ()) {
                        result.add(new BlockPoint(x, y, z));
                    }
                }
            }
        }
        return result;
    }
    public static boolean inConnectedPassage(Archive archive, Room room, BlockPoint point) {
        if (point.y() < Room.FLOOR_Y + 1 || point.y() > Room.FLOOR_Y + 3) return false;
        int dx = point.x() - room.centerX(archive.allocation());
        int dz = point.z() - room.centerZ(archive.allocation());
        for (Direction direction : room.connections().keySet()) {
            if (direction.dx != 0 && Math.abs(dz) <= 1 && dx * direction.dx > 0 && dx * direction.dx <= Room.STRIDE) return true;
            if (direction.dz != 0 && Math.abs(dx) <= 1 && dz * direction.dz > 0 && dz * direction.dz <= Room.STRIDE) return true;
        }
        return false;
    }
    public static List<Bounds> tunnelInteriors(Archive archive) {
        List<Bounds> result = new ArrayList<>();
        for (Room room : archive.rooms().values()) {
            room.connections().forEach((direction, neighborId) -> {
                if (room.id() > neighborId) return;
                Room neighbor = archive.room(neighborId);
                int x1 = room.centerX(archive.allocation()), z1 = room.centerZ(archive.allocation());
                int x2 = neighbor.centerX(archive.allocation()), z2 = neighbor.centerZ(archive.allocation());
                result.add(new Bounds(Math.min(x1, x2) - (direction.dx == 0 ? 1 : 0), Room.FLOOR_Y + 1,
                    Math.min(z1, z2) - (direction.dz == 0 ? 1 : 0), Math.max(x1, x2) + (direction.dx == 0 ? 1 : 0),
                    Room.FLOOR_Y + 3, Math.max(z1, z2) + (direction.dz == 0 ? 1 : 0)));
            });
        }
        return List.copyOf(result);
    }
}
