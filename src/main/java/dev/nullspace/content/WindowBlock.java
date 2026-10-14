package dev.nullspace.content;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import dev.nullspace.core.WindowPlane;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import java.util.ArrayDeque;

// framed opening that shows the archive sky. behaves like glass for rendering (faces between same facing windows
// skipped, no ambient occlusion darkening); collision, hardness and protection are the shell's. models come from
// make_window_model.py
public final class WindowBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
    public static final BooleanProperty TOP = BooleanProperty.create("top"), BOTTOM = BooleanProperty.create("bottom"), LEFT = BooleanProperty.create("left"), RIGHT = BooleanProperty.create("right");
    public static final BooleanProperty POST_TL = BooleanProperty.create("post_tl"), POST_TR = BooleanProperty.create("post_tr"), POST_BL = BooleanProperty.create("post_bl"), POST_BR = BooleanProperty.create("post_br");
    private static final BooleanProperty[] SIDE_PROPS = {TOP, BOTTOM, LEFT, RIGHT};
    private static final BooleanProperty[] POST_PROPS = {POST_TL, POST_TR, POST_BL, POST_BR};
    public static final MapCodec<WindowBlock> CODEC = simpleCodec(WindowBlock::new);

    public WindowBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.Z).setValue(TOP, false).setValue(BOTTOM, false).setValue(LEFT, false).setValue(RIGHT, false)
            .setValue(POST_TL, false).setValue(POST_TR, false).setValue(POST_BL, false).setValue(POST_BR, false));
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(AXIS, TOP, BOTTOM, LEFT, RIGHT, POST_TL, POST_TR, POST_BL, POST_BR); }

    static Direction.Axis openingAxis(BlockGetter level, BlockPos pos, Direction.Axis fallback) {
        Direction.Axis best = fallback;
        int bestWalls = walls(level, pos, fallback);
        for (Direction.Axis axis : Direction.Axis.values()) {
            int n = walls(level, pos, axis);
            if (n < bestWalls) { best = axis; bestWalls = n; }
        }
        return best;
    }

    private static int walls(BlockGetter level, BlockPos pos, Direction.Axis axis) {
        int n = 0;
        for (Direction.AxisDirection d : Direction.AxisDirection.values()) {
            Block b = level.getBlockState(pos.relative(Direction.fromAxisAndDirection(axis, d))).getBlock();
            if (b instanceof ShellBlock || b instanceof WindowBlock) n++;
        }
        return n;
    }

    private static boolean pane(BlockGetter level, BlockPos pos, Direction.Axis axis) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof WindowBlock && s.getValue(AXIS) == axis;
    }

    private static boolean pane(BlockGetter level, BlockPos pos, Direction.Axis axis, int[] offset) {
        return pane(level, pos.offset(offset[0], offset[1], offset[2]), axis);
    }

    static BlockState resolve(BlockGetter level, BlockPos pos, BlockState state) {
        return connections(level, pos, state.setValue(AXIS, openingAxis(level, pos, state.getValue(AXIS))));
    }

    // recomputes connections and posts for the axis the block already has. the room builder uses it after an edit:
    // while a wall is rebuilt the neighbour derived axis depends on placement order (beside a doorway a window can
    // see the tunnel side wall on both sides), so it sets the axis itself
    public static BlockState connections(BlockGetter level, BlockPos pos, BlockState state) {
        Direction.Axis axis = state.getValue(AXIS);
        int a = axis.ordinal();
        boolean[] side = new boolean[4];
        for (WindowPlane.Side s : WindowPlane.Side.values()) {
            side[s.ordinal()] = pane(level, pos, axis, WindowPlane.offset(a, s));
            state = state.setValue(SIDE_PROPS[s.ordinal()], side[s.ordinal()]);
        }
        for (WindowPlane.Corner c : WindowPlane.Corner.values()) {
            boolean post = WindowPlane.post(side[c.vertical.ordinal()], side[c.horizontal.ordinal()], pane(level, pos, axis, WindowPlane.diagonal(a, c)));
            state = state.setValue(POST_PROPS[c.ordinal()], post);
        }
        return state;
    }

    public static boolean isStale(BlockGetter level, BlockPos pos, BlockState state) { return resolve(level, pos, state) != state; }

    public static boolean heal(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof WindowBlock)) return false;
        BlockState fixed = resolve(level, pos, state);
        if (fixed == state) return false;
        level.setBlock(pos, fixed, Block.UPDATE_CLIENTS);
        refreshAround(level, pos);
        return true;
    }

    public static void refreshAround(Level level, BlockPos origin) {
        ArrayDeque<BlockPos> work = new ArrayDeque<>();
        pushNeighbours(work, origin);
        int budget = 512;
        while (!work.isEmpty() && budget-- > 0) {
            BlockPos p = work.poll();
            BlockState s = level.getBlockState(p);
            if (!(s.getBlock() instanceof WindowBlock)) continue;
            BlockState n = resolve(level, p, s);
            if (n != s) { level.setBlock(p, n, Block.UPDATE_CLIENTS); pushNeighbours(work, p); }
        }
    }

    private static void pushNeighbours(ArrayDeque<BlockPos> work, BlockPos p) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dy != 0 || dz != 0) work.add(p.offset(dx, dy, dz));
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (level.isClientSide || oldState.is(this)) return; // a state change of an existing window is one of our own refreshes
        BlockState fixed = resolve(level, pos, state);
        if (fixed != state) level.setBlock(pos, fixed, Block.UPDATE_CLIENTS);
        refreshAround(level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!level.isClientSide && !newState.is(this)) refreshAround(level, pos);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return resolve(level, pos, state);
    }

    @Override protected boolean isRandomlyTicking(BlockState state) { return true; }

    // backstop for the self heal: a window the chunk load pass missed catches up on a random tick
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) { heal(level, pos); }

    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacentState, Direction direction) {
        return (adjacentState.is(this) && adjacentState.getValue(AXIS) == state.getValue(AXIS)) || super.skipRendering(state, adjacentState, direction);
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) { return 1.0F; }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }
}
