package dev.nullspace.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

// validates a recorded return point right before the exit sends someone there. the world may have changed (blocks on
// the spot, lava, a missing platform) and tempad teleports to the exact coordinates, so this is the only place that
// can keep a visitor out of stone, lava or the void
final class ReturnSafety {
    private ReturnSafety() {}

    static Vec3 safeSpot(ServerLevel level, Vec3 wanted) {
        BlockPos base = BlockPos.containing(wanted);
        if (freeAt(level, base) && supported(level, base)) return wanted;
        int[] dys = {0, 1, -1, 2, -2, 3, -3};
        for (int r = 1; r <= 3; r++)
            for (int dy : dys)
                for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos p = base.offset(dx, dy, dz);
                        if (freeAt(level, p) && supported(level, p)) return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                    }
        // Mid-air entry points (jumping, falling) are fine when there is ground below to land on and nothing solid at the spot itself.
        if (freeAt(level, base) && groundBelow(level, base, 64)) return wanted;
        return null;
    }

    static Vec3 spawnSpot(ServerLevel overworld) {
        BlockPos spawn = overworld.getSharedSpawnPos();
        BlockPos top = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn);
        return new Vec3(top.getX() + 0.5, Math.max(top.getY(), overworld.getMinBuildHeight() + 1), top.getZ() + 0.5);
    }

    private static boolean inWorld(ServerLevel level, BlockPos feet) {
        return feet.getY() > level.getMinBuildHeight() && feet.getY() + 1 < level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 1;
    }

    private static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()
            && !state.is(BlockTags.FIRE) && !state.is(Blocks.COBWEB) && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.POWDER_SNOW);
    }

    private static boolean freeAt(ServerLevel level, BlockPos feet) {
        return inWorld(level, feet) && passable(level, feet) && passable(level, feet.above());
    }

    private static boolean harmless(BlockState state) {
        return !state.is(Blocks.MAGMA_BLOCK) && !state.is(Blocks.CAMPFIRE) && !state.is(Blocks.SOUL_CAMPFIRE) && !state.is(Blocks.CACTUS)
            && !state.is(Blocks.WITHER_ROSE) && !state.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean supported(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState state = level.getBlockState(below);
        return state.isFaceSturdy(level, below, Direction.UP) && harmless(state);
    }

    private static boolean groundBelow(ServerLevel level, BlockPos feet, int depth) {
        for (int i = 1; i <= depth; i++) {
            BlockPos p = feet.below(i);
            if (p.getY() <= level.getMinBuildHeight()) return false;
            BlockState state = level.getBlockState(p);
            if (state.getFluidState().is(FluidTags.LAVA)) return false;
            if (state.isFaceSturdy(level, p, Direction.UP)) return harmless(state);
            if (!state.getFluidState().isEmpty()) return true; // water: lands softly
        }
        return false;
    }
}
