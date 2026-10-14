package dev.nullspace.server;

import dev.nullspace.content.Content;
import dev.nullspace.content.ControlBlock;
import dev.nullspace.content.LiftPadBlock;
import dev.nullspace.core.Archive;
import dev.nullspace.core.BlockPoint;
import dev.nullspace.core.Basement;
import dev.nullspace.core.Bounds;
import dev.nullspace.core.Room;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

// world edits for the waypoint hall (layout in Basement). all of it is idempotent so a half finished install can be
// rerun or undone. blocks go through RoomBuilder.set so the crash injection hook covers them
final class HallBuilder {
    private HallBuilder() {}

    // whether every chunk column the hall touches is loaded. getChunkNow because hasChunk blocks the server thread
    // while a chunk is still loading (measured: a one second tick)
    static boolean chunksLoaded(ServerLevel level, Archive archive) {
        for (var c : Basement.chunkColumns(archive.allocation())) if (level.getChunkSource().getChunkNow(c.x(), c.z()) == null) return false;
        return true;
    }

    // reason key why the hall cannot be built now, or null. existing hall blocks do not count as in the way, so a
    // repeated install works
    static String blocked(ServerLevel level, Archive archive) {
        Bounds shell = Basement.shell(archive.allocation());
        for (int x = shell.minX(); x <= shell.maxX(); x++) for (int y = shell.minY(); y <= shell.maxY(); y++) for (int z = shell.minZ(); z <= shell.maxZ(); z++) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.isAir() && !AutomationGuard.structural(state)) return "message.nullspace.waypoint.blocked";
        }
        BlockPoint pad = Basement.upperPad(archive.allocation());
        BlockState floor = level.getBlockState(new BlockPos(pad.x(), pad.y(), pad.z()));
        if (!RoomBuilder.structural(floor) && !floor.is(Content.LIFT_PAD.get())) return "message.nullspace.waypoint.no_floor";
        return null;
    }

    private static String finishAt(int u, int y, int w) {
        if (y == Basement.FLOOR_Y) return Basement.FLOOR_FINISH;
        if (y == Basement.CEILING_Y) return Math.abs(u) <= 1 && Math.abs(w) <= 1 ? Basement.LENS_FINISH : Basement.CEILING_FINISH;
        return Basement.WALL_FINISH;
    }

    private static void put(ServerLevel level, BlockPoint p, BlockState state) { put(level, new BlockPos(p.x(), p.y(), p.z()), state); }
    private static void put(ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.getBlockState(pos).equals(state)) RoomBuilder.set(level, pos, state);
    }

    static void build(ServerLevel level, Archive archive) {
        var a = archive.allocation();
        int cx = Basement.centerX(a), cz = Basement.centerZ(a);
        Bounds shell = Basement.shell(a), interior = Basement.interior(a);
        for (int x = shell.minX(); x <= shell.maxX(); x++) for (int y = shell.minY(); y <= shell.maxY(); y++) for (int z = shell.minZ(); z <= shell.maxZ(); z++) {
            if (interior.contains(x, y, z)) continue;
            int u = x - cx, w = z - cz;
            Basement.Cell cell = Basement.classify(u, y, w);
            BlockState state;
            if (cell == Basement.Cell.DAIS) state = Content.WAYPOINT_DAIS.get().defaultBlockState();
            else if (cell == Basement.Cell.SLOT) state = Content.WAYPOINT_PEDESTAL.get().defaultBlockState();
            else if (u == 0 && w == 0 && y == Basement.FLOOR_Y) state = Content.LIFT_PAD.get().defaultBlockState().setValue(LiftPadBlock.WAY, LiftPadBlock.Way.UP);
            else state = RoomBuilder.shellState(finishAt(u, y, w));
            put(level, new BlockPos(x, y, z), state);
        }
        for (BlockPoint p : Basement.pillars(a)) put(level, p, Content.LAMP_PILLAR.get().defaultBlockState());
        var south = Direction.SOUTH;
        put(level, Basement.terminal(a), Content.TERMINAL.get().defaultBlockState().setValue(ControlBlock.FACING, south));
        BlockState exit = Content.EXIT.get().defaultBlockState().setValue(ControlBlock.FACING, south);
        put(level, Basement.exit(a), exit);
        put(level, Basement.exitUpper(a), exit.setValue(ControlBlock.HALF, DoubleBlockHalf.UPPER));
        // the way down appears last: until the whole hall stands, the starter room's floor is untouched.
        put(level, Basement.upperPad(a), Content.LIFT_PAD.get().defaultBlockState().setValue(LiftPadBlock.WAY, LiftPadBlock.Way.DOWN));
    }

    static void rollback(ServerLevel level, Archive archive) {
        var a = archive.allocation();
        Bounds shell = Basement.shell(a);
        for (int x = shell.minX(); x <= shell.maxX(); x++) for (int y = shell.minY(); y <= shell.maxY(); y++) for (int z = shell.minZ(); z <= shell.maxZ(); z++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (AutomationGuard.structural(level.getBlockState(pos))) RoomBuilder.set(level, pos, Blocks.AIR.defaultBlockState());
        }
        restoreFloor(level, archive);
    }

    static void restoreFloor(ServerLevel level, Archive archive) {
        BlockPoint pad = Basement.upperPad(archive.allocation());
        BlockPos pos = new BlockPos(pad.x(), pad.y(), pad.z());
        if (level.getBlockState(pos).is(Content.LIFT_PAD.get()) || level.getBlockState(pos).isAir()) {
            Room room = archive.room(Room.STARTER_ID);
            RoomBuilder.placeShell(level, archive, room, pos.getX(), pos.getY(), pos.getZ(), room.shell(archive.allocation()));
        }
    }

    static boolean standing(ServerLevel level, Archive archive) {
        var a = archive.allocation();
        BlockPoint up = Basement.upperPad(a), down = Basement.lowerPad(a), term = Basement.terminal(a);
        return level.getBlockState(new BlockPos(up.x(), up.y(), up.z())).is(Content.LIFT_PAD.get())
            && level.getBlockState(new BlockPos(down.x(), down.y(), down.z())).is(Content.LIFT_PAD.get())
            && level.getBlockState(new BlockPos(term.x(), term.y(), term.z())).is(Content.TERMINAL.get());
    }
}
