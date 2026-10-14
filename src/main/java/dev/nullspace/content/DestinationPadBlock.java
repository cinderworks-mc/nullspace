package dev.nullspace.content;

import com.mojang.serialization.MapCodec;
import dev.nullspace.core.Basement;
import dev.nullspace.server.HubPads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

public final class DestinationPadBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final MapCodec<DestinationPadBlock> CODEC = simpleCodec(DestinationPadBlock::new);
    public static final double HEIGHT = 3 / 16.0;
    public static final BooleanProperty BOUND = BooleanProperty.create("bound");
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);
    static {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (double[] b : HallGeometry.PAD_PARTS) {
                double[] r = rotate(b, d);
                shape = Shapes.or(shape, Block.box(r[0], r[1], r[2], r[3], r[4], r[5]));
            }
            SHAPES.put(d, shape.optimize());
        }
    }
    static double[] rotate(double[] b, Direction facing) {
        double x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
        return switch (facing) {
            case EAST -> new double[] {16 - z1, b[1], x0, 16 - z0, b[4], x1};
            case SOUTH -> new double[] {16 - x1, b[1], 16 - z1, 16 - x0, b[4], 16 - z0};
            case WEST -> new double[] {z0, b[1], 16 - x1, z1, b[4], 16 - x0};
            default -> b;
        };
    }

    public DestinationPadBlock(BlockBehaviour.Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(BOUND, false)); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, BOUND); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPES.get(state.getValue(FACING)); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DestinationPadBlockEntity(pos, state); }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        int slot = Basement.slotAtNearest(pos.getX(), pos.getY(), pos.getZ());
        Direction facing = slot < 0 ? context.getHorizontalDirection().getOpposite() : toMc(Basement.slotFacing(slot));
        return defaultBlockState().setValue(FACING, facing);
    }

    public static Direction toMc(dev.nullspace.core.Direction d) {
        return switch (d) { case NORTH -> Direction.NORTH; case EAST -> Direction.EAST;
            case SOUTH -> Direction.SOUTH; case WEST -> Direction.WEST; };
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide()) HubPads.syncPad(level, pos);
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp) HubPads.use(sp, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
