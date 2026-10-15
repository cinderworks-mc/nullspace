package dev.nullspace.server;

import dev.nullspace.content.Content;
import dev.nullspace.content.ShellBlock;
import dev.nullspace.content.WindowBlock;
import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.BlockPoint;
import dev.nullspace.core.Bounds;
import dev.nullspace.core.Direction;
import dev.nullspace.core.LayoutPlanner;
import dev.nullspace.core.Room;
import dev.nullspace.core.Surface;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RoomBuilder {
    private RoomBuilder() {}

    // 30 second tickets so a previewed edit finds its chunks already loaded and does not load them in the confirming
    // tick
    private static final TicketType<ChunkPos> EDIT_TICKET =
        TicketType.create("nullspace_edit", Comparator.comparingLong(ChunkPos::toLong), 600);

    public static int prewarm(ServerLevel level, Bounds... areas) {
        Set<ChunkPos> chunks = new HashSet<>();
        for (Bounds b : areas) {
            for (int cx = Math.floorDiv(b.minX() - 1, 16); cx <= Math.floorDiv(b.maxX() + 1, 16); cx++)
                for (int cz = Math.floorDiv(b.minZ() - 1, 16); cz <= Math.floorDiv(b.maxZ() + 1, 16); cz++) chunks.add(new ChunkPos(cx, cz));
        }
        for (var pos : chunks) level.getChunkSource().addRegionTicket(EDIT_TICKET, pos, ArchiveService.ticketDistance, pos);
        return chunks.size();
    }

    private static long edits;
    static long editCount() { return edits; }

    static void set(ServerLevel level, BlockPos pos, BlockState state) {
        edits++;
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    static Surface surfaceOf(int x, int y, int z, Bounds shell) { return LayoutPlanner.surfaceOf(x, y, z, shell); }

    static Room.FinishCoordinate coordinate(Room room, Archive archive, Surface surface, int x, int y, int z) {
        int cx = room.centerX(archive.allocation()), cz = room.centerZ(archive.allocation());
        return switch (surface) {
            case NORTH, SOUTH -> new Room.FinishCoordinate(surface, x - cx, y - Room.FLOOR_Y);
            case EAST, WEST -> new Room.FinishCoordinate(surface, z - cz, y - Room.FLOOR_Y);
            case FLOOR, CEILING -> new Room.FinishCoordinate(surface, x - cx, z - cz);
        };
    }

    static boolean structural(BlockState state) {
        Block block = state.getBlock();
        return block instanceof ShellBlock || block == Content.WINDOW.get();
    }

    public static BlockState shellState(String finish) {
        if (Finishes.WINDOW.equals(finish)) return Content.WINDOW.get().defaultBlockState();
        String name = finish.substring(finish.indexOf(':') + 1).toUpperCase(Locale.ROOT);
        return Content.SHELL.get().defaultBlockState().setValue(ShellBlock.FINISH, ShellBlock.Finish.valueOf(name));
    }

    static void placeShell(ServerLevel level, Archive archive, Room room, int x, int y, int z, Bounds shell) {
        Surface surface = surfaceOf(x, y, z, shell);
        String finish = room.finish(coordinate(room, archive, surface, x, y, z));
        BlockPos pos = new BlockPos(x, y, z);
        BlockState wanted = shellState(finish);
        // a window that is already there stays as it is: replacing it by a default-state window would reset its axis and its connections
        // (WindowBlock.onPlace ignores a window replacing a window), and nothing would refresh them.
        if (wanted.is(Content.WINDOW.get()) && level.getBlockState(pos).is(Content.WINDOW.get())) return;
        if (wanted.is(Content.WINDOW.get())) {
            // a window in the middle of a wall looks along that wall's normal; said here rather than derived from neighbours that may not be placed yet.
            var normal = wallNormal(shell, x, y, z);
            if (normal != null) wanted = wanted.setValue(WindowBlock.AXIS, normal);
        }
        set(level, pos, wanted);
    }

    static net.minecraft.core.Direction.Axis wallNormal(Bounds shell, int x, int y, int z) {
        int planes = (x == shell.minX() || x == shell.maxX() ? 1 : 0) + (y == shell.minY() || y == shell.maxY() ? 1 : 0) + (z == shell.minZ() || z == shell.maxZ() ? 1 : 0);
        if (planes != 1) return null;
        return x == shell.minX() || x == shell.maxX() ? net.minecraft.core.Direction.Axis.X : y == shell.minY() || y == shell.maxY() ? net.minecraft.core.Direction.Axis.Y : net.minecraft.core.Direction.Axis.Z;
    }

    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    // settles every window of a shell after an edit: axis from its wall, then the connections its neighbours imply.
    // neither depends on placement order. not an edit (no kill points, no neighbour updates), idempotent
    public static void settleWindows(ServerLevel level, Bounds shell) {
        for (int x = shell.minX(); x <= shell.maxX(); x++) for (int y = shell.minY(); y <= shell.maxY(); y++) for (int z = shell.minZ(); z <= shell.maxZ(); z++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(pos);
            var axis = wallNormal(shell, x, y, z);
            if (axis != null && s.is(Content.WINDOW.get()) && s.getValue(WindowBlock.AXIS) != axis)
                level.setBlock(pos, s.setValue(WindowBlock.AXIS, axis), QUIET);
        }
        for (int x = shell.minX(); x <= shell.maxX(); x++) for (int y = shell.minY(); y <= shell.maxY(); y++) for (int z = shell.minZ(); z <= shell.maxZ(); z++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(pos);
            if (!s.is(Content.WINDOW.get())) continue;
            BlockState c = WindowBlock.connections(level, pos, s);
            if (c != s) level.setBlock(pos, c, QUIET);
        }
    }

    public static int repaintSurface(ServerLevel level, Archive archive, Room room, Surface surface) {
        Bounds shell = room.shell(archive.allocation());
        int changed = 0;
        for (BlockPoint p : LayoutPlanner.shellBlocks(archive, room, room.width())) {
            if (surfaceOf(p.x(), p.y(), p.z(), shell) != surface || LayoutPlanner.inConnectedPassage(archive, room, p)) continue;
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            if (!structural(level.getBlockState(pos))) continue;
            placeShell(level, archive, room, p.x(), p.y(), p.z(), shell);
            changed++;
        }
        if (changed > 0) settleWindows(level, shell);
        return changed;
    }

    static Optional<Room> roomOfShell(Archive archive, BlockPos pos) {
        return archive.rooms().values().stream().filter(r -> r.shell(archive.allocation()).contains(pos.getX(), pos.getY(), pos.getZ())).findFirst();
    }

    public static void placeShellFor(ServerLevel level, Archive archive, Room room) { buildShell(level, archive, room); }

    static void buildShell(ServerLevel level, Archive archive, Room room) {
        Bounds shell = room.shell(archive.allocation());
        for (BlockPoint p : LayoutPlanner.shellBlocks(archive, room, room.width())) placeShell(level, archive, room, p.x(), p.y(), p.z(), shell);
        settleWindows(level, shell);
    }

    // call before committing the domain change. walls move out (or the ceiling rises) and the interior stays. new
    // walls go first so every step is a sealed room, old walls last. idempotent, rerunning after an interruption
    // finishes the job. raising the ceiling removes the old ceiling plane, which players hang things from; anything
    // in the top row that loses its support stays put and gets a chain up to the new ceiling (holdUpHangers)
    public static void applyExpansion(ServerLevel level, Archive archive, LayoutPlanner.ExpansionPlan plan) {
        Room room = archive.room(plan.roomId());
        Bounds newShell = room.bounds(archive.allocation(), plan.newWidth(), plan.newHeight(), true);
        for (BlockPoint p : plan.addShell()) placeShell(level, archive, room, p.x(), p.y(), p.z(), newShell);
        for (BlockPoint p : plan.restyleShell()) {
            if (structural(level.getBlockState(new BlockPos(p.x(), p.y(), p.z())))) placeShell(level, archive, room, p.x(), p.y(), p.z(), newShell);
        }
        if (plan.axis() == LayoutPlanner.Axis.HEIGHT) holdUpHangers(level, plan.oldInterior(), plan.newInterior().maxY() - plan.oldInterior().maxY());
        for (BlockPoint p : plan.removeShell()) {
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            if (structural(level.getBlockState(pos))) set(level, pos, Blocks.AIR.defaultBlockState());
        }
        clearStructural(level, plan.newInterior());
        settleWindows(level, newShell);
    }

    private static final int PROBE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    // blocks in the top interior row that need the ceiling above (lanterns, hanging signs, bells) or are a chain
    // already hanging from it
    private static boolean hangsFromCeiling(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || structural(state)) return false;
        if (state.getBlock() instanceof ChainBlock && state.getValue(ChainBlock.AXIS) == net.minecraft.core.Direction.Axis.Y) return true;
        BlockPos above = pos.above();
        BlockState ceiling = level.getBlockState(above);
        if (!structural(ceiling)) return false;
        // ask the block itself: take the ceiling away without telling anyone, see whether it would survive, put the ceiling back.
        level.setBlock(above, Blocks.AIR.defaultBlockState(), PROBE_FLAGS);
        boolean survives = state.canSurvive(level, pos);
        level.setBlock(above, ceiling, PROBE_FLAGS);
        return !survives;
    }

    // hanging blocks in the top row keep their place: a chain column replaces the old ceiling cell and the rows up to
    // the new ceiling
    static int holdUpHangers(ServerLevel level, Bounds oldInterior, int delta) {
        BlockState chain = Blocks.CHAIN.defaultBlockState();
        int held = 0, y = oldInterior.maxY();
        for (int x = oldInterior.minX(); x <= oldInterior.maxX(); x++) for (int z = oldInterior.minZ(); z <= oldInterior.maxZ(); z++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!hangsFromCeiling(level, pos, level.getBlockState(pos))) continue;
            for (int i = 1; i <= delta; i++) set(level, pos.above(i), chain);
            held++;
        }
        return held;
    }

    private static void removeHangerChains(ServerLevel level, Bounds oldInterior, int newTopY) {
        for (int x = oldInterior.minX(); x <= oldInterior.maxX(); x++) for (int z = oldInterior.minZ(); z <= oldInterior.maxZ(); z++) {
            for (int y = oldInterior.maxY() + 1; y <= newTopY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (level.getBlockState(pos).is(Blocks.CHAIN)) set(level, pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    public static void restoreExpansion(ServerLevel level, Archive archive, int roomId) { restoreExpansion(level, archive, roomId, LayoutPlanner.Axis.WIDTH); }

    public static void restoreExpansion(ServerLevel level, Archive archive, int roomId, LayoutPlanner.Axis axis) {
        var plan = LayoutPlanner.previewExpansion(archive, archive.owner(), roomId, axis);
        Room room = archive.room(roomId);
        Bounds oldShell = room.shell(archive.allocation());
        // the old roof goes back first (over a hanger's chain link where there is one), so whatever hangs below keeps its support;
        // only then are the rest of the chain links taken away.
        for (BlockPoint p : plan.removeShell()) {
            BlockState state = level.getBlockState(new BlockPos(p.x(), p.y(), p.z()));
            if (state.isAir() || structural(state) || (axis == LayoutPlanner.Axis.HEIGHT && state.is(Blocks.CHAIN))) placeShell(level, archive, room, p.x(), p.y(), p.z(), oldShell);
        }
        if (axis == LayoutPlanner.Axis.HEIGHT) removeHangerChains(level, plan.oldInterior(), plan.newInterior().maxY());
        for (BlockPoint p : plan.restyleShell()) {
            if (structural(level.getBlockState(new BlockPos(p.x(), p.y(), p.z())))) placeShell(level, archive, room, p.x(), p.y(), p.z(), oldShell);
        }
        for (BlockPoint p : plan.addShell()) {
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            if (structural(level.getBlockState(pos))) set(level, pos, Blocks.AIR.defaultBlockState());
        }
        // enlarging also swallowed tunnel walls that stood inside the new room; put them back.
        room.connections().forEach((direction, neighborId) -> placeTunnelWalls(level, archive, room, archive.room(neighborId), direction, true));
        settleWindows(level, oldShell);
    }

    static void clearStructural(ServerLevel level, Bounds bounds) {
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) for (int y = bounds.minY(); y <= bounds.maxY(); y++) for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (structural(level.getBlockState(pos))) set(level, pos, Blocks.AIR.defaultBlockState());
        }
    }

    static Bounds tunnelInterior(Archive archive, Room a, Room b, Direction direction) {
        int x1 = a.centerX(archive.allocation()), z1 = a.centerZ(archive.allocation());
        int x2 = b.centerX(archive.allocation()), z2 = b.centerZ(archive.allocation());
        return new Bounds(Math.min(x1, x2) - (direction.dx == 0 ? 1 : 0), Room.FLOOR_Y + 1, Math.min(z1, z2) - (direction.dz == 0 ? 1 : 0),
            Math.max(x1, x2) + (direction.dx == 0 ? 1 : 0), Room.FLOOR_Y + 3, Math.max(z1, z2) + (direction.dz == 0 ? 1 : 0));
    }

    public static void applyAnnex(ServerLevel level, Archive archive, Room source, Room added, Direction direction) {
        buildShell(level, archive, added);
        placeTunnelWalls(level, archive, source, added, direction, false);
        clearStructural(level, tunnelInterior(archive, source, added, direction));
        replaceDoorwayWindows(level, archive, source, direction);
        settleWindows(level, source.shell(archive.allocation()));
    }

    // the starter window's top row sits in the doorway frame and would show through the hallway ceiling
    static void replaceDoorwayWindows(ServerLevel level, Archive archive, Room source, Direction direction) {
        Bounds shell = source.shell(archive.allocation());
        int cx = source.centerX(archive.allocation()), cz = source.centerZ(archive.allocation());
        String surfaceFinish = source.finishes().get(Surface.valueOf(direction.name()));
        String plain = surfaceFinish == null || Finishes.WINDOW.equals(surfaceFinish) ? "nullspace:charcoal" : surfaceFinish;
        for (int u = -Room.DOORWAY_REACH; u <= Room.DOORWAY_REACH; u++) for (int v = 0; v <= Room.DOORWAY_LINTEL; v++) {
            int y = Room.FLOOR_Y + v;
            BlockPos pos = switch (direction) {
                case NORTH -> new BlockPos(cx + u, y, shell.minZ());
                case SOUTH -> new BlockPos(cx + u, y, shell.maxZ());
                case EAST -> new BlockPos(shell.maxX(), y, cz + u);
                case WEST -> new BlockPos(shell.minX(), y, cz + u);
            };
            if (level.getBlockState(pos).is(Content.WINDOW.get())) set(level, pos, shellState(plain));
        }
    }

    static void placeTunnelWalls(ServerLevel level, Archive archive, Room a, Room b, Direction direction, boolean onlyOverAirOrStructural) {
        Bounds tunnel = tunnelInterior(archive, a, b, direction);
        for (int x = tunnel.minX() - 1; x <= tunnel.maxX() + 1; x++) for (int y = Room.FLOOR_Y; y <= Room.FLOOR_Y + 4; y++) for (int z = tunnel.minZ() - 1; z <= tunnel.maxZ() + 1; z++) {
            boolean boundary = x == tunnel.minX() - 1 || x == tunnel.maxX() + 1 || y == Room.FLOOR_Y || y == Room.FLOOR_Y + 4 || z == tunnel.minZ() - 1 || z == tunnel.maxZ() + 1;
            if (!boundary || insideAnyShell(archive, a, b, x, y, z)) continue;
            BlockPos pos = new BlockPos(x, y, z);
            BlockState current = level.getBlockState(pos);
            if (onlyOverAirOrStructural && !current.isAir() && !structural(current)) continue;
            BlockState wanted = shellState("nullspace:charcoal");
            if (!current.equals(wanted)) set(level, pos, wanted);
        }
    }

    public static void rollbackAnnex(ServerLevel level, Archive archive, int sourceId, Direction direction, int newRoomId, int gridX, int gridZ) {
        Room source = archive.room(sourceId);
        Room provisional = new Room(newRoomId, gridX, gridZ, 0);
        clearStructural(level, provisional.reservedBounds(archive.allocation()));
        Bounds tunnel = tunnelInterior(archive, source, provisional, direction);
        for (int x = tunnel.minX() - 1; x <= tunnel.maxX() + 1; x++) for (int y = Room.FLOOR_Y; y <= Room.FLOOR_Y + 4; y++) for (int z = tunnel.minZ() - 1; z <= tunnel.maxZ() + 1; z++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!insideAnyShell(archive, source, provisional, x, y, z) && structural(level.getBlockState(pos))) set(level, pos, Blocks.AIR.defaultBlockState());
        }
        sealDoorway(level, archive, source, direction);
    }

    static void clearRemovedRoom(ServerLevel level, Archive archive, Room leaf, Room neighbour, Direction towardsLeaf) {
        Bounds tunnel = tunnelInterior(archive, neighbour, leaf, towardsLeaf);
        clearStructural(level, leaf.shell(archive.allocation()));
        clearStructural(level, new Bounds(tunnel.minX() - 1, Room.FLOOR_Y, tunnel.minZ() - 1, tunnel.maxX() + 1, Room.FLOOR_Y + 4, tunnel.maxZ() + 1));
    }

    static void restoreRemoval(ServerLevel level, Archive archive, int neighbourId, int leafId) {
        Room neighbour = archive.room(neighbourId), leaf = archive.room(leafId);
        Direction towardsLeaf = neighbour.connections().entrySet().stream().filter(e -> e.getValue() == leafId).map(Map.Entry::getKey).findFirst()
            .orElseThrow(() -> new ArchiveException("error.nullspace.admin.not_connected"));
        for (Room room : new Room[] {leaf, neighbour}) {
            Bounds shell = room.shell(archive.allocation());
            for (BlockPoint p : LayoutPlanner.shellBlocks(archive, room, room.width())) {
                BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
                BlockState state = level.getBlockState(pos);
                if (LayoutPlanner.inConnectedPassage(archive, room, p)) { if (structural(state)) set(level, pos, Blocks.AIR.defaultBlockState()); }
                else if (state.isAir() || structural(state)) placeShell(level, archive, room, p.x(), p.y(), p.z(), shell);
            }
        }
        placeTunnelWalls(level, archive, neighbour, leaf, towardsLeaf, true);
        clearStructural(level, tunnelInterior(archive, neighbour, leaf, towardsLeaf));
        settleWindows(level, neighbour.shell(archive.allocation()));
        settleWindows(level, leaf.shell(archive.allocation()));
    }

    public static void sealDoorway(ServerLevel level, Archive archive, Room room, Direction ignored) {
        Bounds shell = room.shell(archive.allocation());
        for (BlockPoint p : LayoutPlanner.shellBlocks(archive, room, room.width())) {
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            if (level.getBlockState(pos).isAir() && !LayoutPlanner.inConnectedPassage(archive, room, p)) placeShell(level, archive, room, p.x(), p.y(), p.z(), shell);
        }
        settleWindows(level, shell);
    }

    private static boolean insideAnyShell(Archive archive, Room a, Room b, int x, int y, int z) {
        return a.shell(archive.allocation()).contains(x, y, z) || b.shell(archive.allocation()).contains(x, y, z);
    }
}
