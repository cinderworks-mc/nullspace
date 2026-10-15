package dev.nullspace.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.nullspace.server.ArchiveService;
import dev.nullspace.content.Content;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;
import java.util.EnumMap;
import java.util.Map;

public final class ControlBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final MapCodec<ControlBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        Codec.BOOL.fieldOf("exit").forGetter(b -> b.exit), propertiesCodec()).apply(i, ControlBlock::new));
    // the exit is a doorway, not a cube; the model is drawn facing north and turned by the blockstate
    // two blocks tall like a vanilla door: the lower half is the threshold and the foot of the leaf, the upper half the arch.
    // the terminal is always LOWER
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    private static final Map<Direction, VoxelShape> EXIT_LOWER_SHAPES = shapes(HallGeometry.EXIT_LOWER_PARTS);
    private static final Map<Direction, VoxelShape> EXIT_UPPER_SHAPES = shapes(HallGeometry.EXIT_UPPER_PARTS);

    private static Map<Direction, VoxelShape> shapes(double[][] parts) {
        Map<Direction, VoxelShape> map = new EnumMap<>(Direction.class);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (double[] b : parts) {
                double[] r = DestinationPadBlock.rotate(b, d);
                shape = Shapes.or(shape, Block.box(r[0], r[1], r[2], r[3], r[4], r[5]));
            }
            map.put(d, shape.optimize());
        }
        return map;
    }
    // breakable by hand so the owner can rearrange a room, still blast proof. who may is up to ArchiveProtection
    public static BlockBehaviour.Properties handProperties(){return BlockBehaviour.Properties.of().strength(2.0f,3600000).isValidSpawn((s,l,p,e)->false).pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).noOcclusion();}
    private final boolean exit;
    public boolean isExit(){return exit;}
    public ControlBlock(boolean exit, BlockBehaviour.Properties properties){super(properties);this.exit=exit;registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(HALF,DoubleBlockHalf.LOWER));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING,HALF);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected MapCodec<? extends Block> codec(){return CODEC;}
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c){
        if(!exit) return Shapes.block();
        return (s.getValue(HALF)==DoubleBlockHalf.UPPER ? EXIT_UPPER_SHAPES : EXIT_LOWER_SHAPES).get(s.getValue(FACING));
    }

    // the upper half cannot stand without the lower one. a lower half without an upper one (a world saved with the
    // one block exit, or an edit that took the upper away) still works as an exit and puts the upper back next tick
    @Override protected BlockState updateShape(BlockState s, Direction dir, BlockState neighbor, LevelAccessor l, BlockPos p, BlockPos neighborPos){
        if(!exit) return s;
        boolean upper=s.getValue(HALF)==DoubleBlockHalf.UPPER;
        if(dir==(upper?Direction.DOWN:Direction.UP)){
            if(upper) return neighbor.is(this)&&neighbor.getValue(HALF)==DoubleBlockHalf.LOWER ? s : Blocks.AIR.defaultBlockState();
            if(!neighbor.is(this)) l.scheduleTick(p,this,1);
        }
        return s;
    }
    @Override protected void tick(BlockState s, ServerLevel l, BlockPos p, RandomSource r){ if(exit) ensureUpper(l,p); }

    public static boolean ensureUpper(Level l, BlockPos lowerPos){
        BlockState lower=l.getBlockState(lowerPos);
        if(!(lower.getBlock() instanceof ControlBlock c)||!c.exit||lower.getValue(HALF)!=DoubleBlockHalf.LOWER) return false;
        BlockPos up=lowerPos.above();
        if(!l.getBlockState(up).isAir()) return false;
        l.setBlock(up,lower.setValue(HALF,DoubleBlockHalf.UPPER),Block.UPDATE_ALL);
        return true;
    }

    @Override public BlockState playerWillDestroy(Level l, BlockPos p, BlockState s, Player player){
        if(exit&&!l.isClientSide){
            BlockPos other=p.relative(s.getValue(HALF)==DoubleBlockHalf.UPPER?Direction.DOWN:Direction.UP);
            BlockState o=l.getBlockState(other);
            if(o.is(this)&&o.getValue(HALF)!=s.getValue(HALF)) l.setBlock(other,Blocks.AIR.defaultBlockState(),Block.UPDATE_SUPPRESS_DROPS|Block.UPDATE_ALL);
        }
        return super.playerWillDestroy(l,p,s,player);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState s, Level l, BlockPos p, Player player, InteractionHand hand, BlockHitResult hit){
        if(!exit && stack.is(Content.ANCHOR.get())) {
            if(player instanceof ServerPlayer sp) ArchiveService.installAnchor(sp, p, stack);
            return ItemInteractionResult.sidedSuccess(l.isClientSide);
        }
        if(!exit && stack.is(Content.RECALL_MODULE.get())) {
            if(player instanceof ServerPlayer sp) ArchiveService.installRecall(sp, p, stack);
            return ItemInteractionResult.sidedSuccess(l.isClientSide);
        }
        if(!exit && stack.is(Content.WAYPOINT_MODULE.get())) {
            if(player instanceof ServerPlayer sp) ArchiveService.installWaypoint(sp, p, stack);
            return ItemInteractionResult.sidedSuccess(l.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected InteractionResult useWithoutItem(BlockState s, Level l, BlockPos p, Player player, BlockHitResult hit){
        if(exit&&!l.isClientSide) ensureUpper(l,s.getValue(HALF)==DoubleBlockHalf.UPPER?p.below():p);
        if(player instanceof ServerPlayer sp) { if(exit) ArchiveService.exit(sp); else ArchiveService.openTerminal(sp,p); }
        return InteractionResult.sidedSuccess(l.isClientSide);
    }
}
