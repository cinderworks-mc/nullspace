package dev.nullspace.content;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class LampPillarBlock extends Block implements HallBlock {
    public static final MapCodec<LampPillarBlock> CODEC = simpleCodec(LampPillarBlock::new);
    private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 16, 13);
    public LampPillarBlock(BlockBehaviour.Properties properties) { super(properties); }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
}
