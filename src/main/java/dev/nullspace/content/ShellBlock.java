package dev.nullspace.content;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;

import java.util.Locale;

public final class ShellBlock extends Block {
    public enum Finish implements StringRepresentable {
        IVORY, GREEN, CHARCOAL, WOOD, COPPER, AMBER;
        @Override public String getSerializedName(){return name().toLowerCase(Locale.ROOT);}
    }
    public static final EnumProperty<Finish> FINISH = EnumProperty.create("finish", Finish.class);
    public static final MapCodec<ShellBlock> CODEC = simpleCodec(ShellBlock::new);
    public ShellBlock(BlockBehaviour.Properties properties) {super(properties); registerDefaultState(stateDefinition.any().setValue(FINISH,Finish.IVORY));}
    @Override protected MapCodec<? extends Block> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FINISH);}
    public static BlockBehaviour.Properties baseProperties(){return BlockBehaviour.Properties.of().strength(-1,3600000).noLootTable().isValidSpawn((s,l,p,e)->false).pushReaction(PushReaction.BLOCK).lightLevel(s->s.hasProperty(FINISH)&&s.getValue(FINISH)==Finish.AMBER?13:0);}
}
