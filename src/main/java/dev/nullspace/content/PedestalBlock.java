package dev.nullspace.content;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PedestalBlock extends Block implements HallBlock {
    public static final MapCodec<PedestalBlock> CODEC = simpleCodec(PedestalBlock::new);
    // boxes stay separate (see DaisBlock): the cap top is the cell top plane even with the rim on it
    private static final VoxelShape SHAPE = parts();

    private static VoxelShape parts() {
        VoxelShape shape = Shapes.empty();
        for (double[] b : HallGeometry.PEDESTAL_PARTS) shape = Shapes.joinUnoptimized(shape, Block.box(b[0], b[1], b[2], b[3], b[4], b[5]), BooleanOp.OR);
        return shape;
    }

    public PedestalBlock(BlockBehaviour.Properties properties) { super(properties); }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
}
