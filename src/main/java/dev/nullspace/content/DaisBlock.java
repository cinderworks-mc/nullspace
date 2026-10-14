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

public final class DaisBlock extends Block implements HallBlock {
    public static final MapCodec<DaisBlock> CODEC = simpleCodec(DaisBlock::new);
    // two stacked boxes, not merged with Shapes.or. an entity already inside the tier (a teleport to the cell top, a
    // spawn) is still held up by the cell's own top plane; one 0..18 box lets it fall through
    private static final VoxelShape SHAPE = Shapes.joinUnoptimized(Block.box(0, 0, 0, 16, 16, 16),
        Block.box(0, 16, 0, 16, HallGeometry.DAIS_TOP, 16), BooleanOp.OR);
    public DaisBlock(BlockBehaviour.Properties properties) { super(properties); }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
}
