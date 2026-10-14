package dev.nullspace.server;

import dev.nullspace.content.Content;
import dev.nullspace.content.ControlBlock;
import dev.nullspace.core.Archive;
import dev.nullspace.core.BlockPoint;
import dev.nullspace.core.Bounds;
import dev.nullspace.core.LayoutPlanner;
import dev.nullspace.core.Room;
import dev.nullspace.core.Surface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.List;

public final class StarterRooms {
    private StarterRooms() {}

    public static final String CLASSIC = "nullspace:classic";

    public static final int WINDOW_HALF = 1;
    public static final int WINDOW_MIN_V = 2, WINDOW_MAX_V = 4;

    public static List<Room.FinishCoordinate> windowCells() {
        var cells = new ArrayList<Room.FinishCoordinate>();
        for (int v = WINDOW_MIN_V; v <= WINDOW_MAX_V; v++) for (int u = -WINDOW_HALF; u <= WINDOW_HALF; u++) cells.add(new Room.FinishCoordinate(Surface.SOUTH, u, v));
        return cells;
    }

    // result of enlargeWindow: blocks turned into window, blocks left alone because the owner painted them or a
    // doorway runs there
    public record Enlarged(int changed, int kept, int doorway) {}

    public static Enlarged enlargeWindow(ServerLevel level, Archive archive) {
        Room room = archive.room(Room.STARTER_ID);
        Bounds shell = room.shell(archive.allocation());
        int changed = 0, kept = 0, doorway = 0;
        for (Room.FinishCoordinate cell : windowCells()) {
            BlockPos pos = new BlockPos(room.centerX(archive.allocation()) + cell.u(), Room.FLOOR_Y + cell.v(), shell.maxZ());
            if (LayoutPlanner.inConnectedPassage(archive, room, new BlockPoint(pos.getX(), pos.getY(), pos.getZ()))) { doorway++; continue; }
            String own = room.individualFinishes().get(cell);
            if (own != null && !Finishes.WINDOW.equals(own)) { kept++; continue; }
            room.setIndividualFinish(cell, Finishes.WINDOW);
            if (!level.getBlockState(pos).is(Content.WINDOW.get())) { RoomBuilder.placeShell(level, archive, room, pos.getX(), pos.getY(), pos.getZ(), shell); changed++; }
        }
        RoomBuilder.settleWindows(level, shell);
        return new Enlarged(changed, kept, doorway);
    }

    public static void place(ServerLevel level, Archive archive) {
        String preset = PresetManager.INSTANCE.get(ResourceLocation.parse(archive.starterPreset())).isPresent() ? archive.starterPreset() : CLASSIC;
        place(level, archive, preset);
    }

    private static void place(ServerLevel level, Archive archive, String preset) {
        Room room = archive.room(0);
        Bounds shell = room.shell(archive.allocation());
        Bounds interior = room.interior(archive.allocation());
        int cx = room.centerX(archive.allocation());
        int cz = room.centerZ(archive.allocation());

        // starfield window, centred on the south wall (opposite the terminal), 3 x 3. Stored as per-block finishes so it survives enlargement.
        for (Room.FinishCoordinate cell : windowCells()) room.setIndividualFinish(cell, Finishes.WINDOW);
        RoomBuilder.buildShell(level, archive, room);

        int floorY = interior.minY();
        level.setBlock(new BlockPos(cx, floorY, interior.minZ()), Content.TERMINAL.get().defaultBlockState().setValue(ControlBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        BlockState exit = Content.EXIT.get().defaultBlockState().setValue(ControlBlock.FACING, Direction.SOUTH);
        level.setBlock(new BlockPos(cx - 1, floorY, interior.minZ()), exit, Block.UPDATE_ALL);
        level.setBlock(new BlockPos(cx - 1, floorY + 1, interior.minZ()), exit.setValue(ControlBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        PresetManager.INSTANCE.get(ResourceLocation.parse(preset)).ifPresent(p -> p.blocks().forEach(entry ->
            PresetManager.resolve(entry).ifPresent(state -> level.setBlock(PresetManager.at(cx, floorY, cz, entry), state, Block.UPDATE_ALL))));
    }
}
