package dev.nullspace.content;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;

public final class LiftPadBlock extends Block implements HallBlock {
    public enum Way implements StringRepresentable {
        DOWN, UP;
        @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }
    public static final EnumProperty<Way> WAY = EnumProperty.create("way", Way.class);
    public static final MapCodec<LiftPadBlock> CODEC = simpleCodec(LiftPadBlock::new);

    // the down end has a rim 3 above the cell top; the cube stays its own box so the landing at the cell top is as before
    private static final VoxelShape DOWN_SHAPE = parts();

    private static VoxelShape parts() {
        VoxelShape shape = Shapes.empty();
        for (double[] b : HallGeometry.LIFT_DOWN_PARTS) shape = Shapes.joinUnoptimized(shape, Block.box(b[0], b[1], b[2], b[3], b[4], b[5]), BooleanOp.OR);
        return shape;
    }

    public LiftPadBlock(BlockBehaviour.Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(WAY, Way.DOWN)); }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(WAY); }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return state.getValue(WAY) == Way.DOWN ? DOWN_SHAPE : Shapes.block(); }

    @Override public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(6) != 0) return;
        boolean up = state.getValue(WAY) == Way.UP;
        level.addParticle(up ? ParticleTypes.END_ROD : ParticleTypes.REVERSE_PORTAL, pos.getX() + 0.2 + random.nextDouble() * 0.6, pos.getY() + 1.02,
            pos.getZ() + 0.2 + random.nextDouble() * 0.6, 0, up ? 0.03 : -0.02, 0);
    }
}
