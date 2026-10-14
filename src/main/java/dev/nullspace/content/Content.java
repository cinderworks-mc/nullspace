package dev.nullspace.content;

import com.mojang.serialization.Codec;
import dev.nullspace.Nullspace;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

import java.util.function.Supplier;

public final class Content {
    public static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(Nullspace.ID);
    public static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(Nullspace.ID);
    public static final DeferredRegister.DataComponents COMPONENTS=DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE,Nullspace.ID);
    public static final Supplier<DataComponentType<String>> SELECTED_FINISH=COMPONENTS.registerComponentType("selected_finish",b->b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));
    public static final DeferredRegister<SoundEvent> SOUNDS=DeferredRegister.create(Registries.SOUND_EVENT,Nullspace.ID);
    private static DeferredHolder<SoundEvent,SoundEvent> sound(String n){return SOUNDS.register(n,()->SoundEvent.createVariableRangeEvent(Nullspace.id(n)));}
    public static final DeferredHolder<SoundEvent,SoundEvent> TERMINAL_OPEN=sound("terminal_open"),TERMINAL_CONFIRM=sound("terminal_confirm"),TERMINAL_DENY=sound("terminal_deny"),
        EXIT_OPEN=sound("exit_open"),APPLICATOR_SOUND=sound("applicator"),ROOM_CHANGE=sound("room_change"),ANCHOR_INSTALL=sound("anchor_install");
    public static final DeferredRegister<CreativeModeTab> TABS=DeferredRegister.create(Registries.CREATIVE_MODE_TAB,Nullspace.ID);
    public static final DeferredBlock<ShellBlock> SHELL=BLOCKS.register("shell",()->new ShellBlock(ShellBlock.baseProperties()));
    public static final DeferredBlock<Block> WINDOW=BLOCKS.register("shell_window",()->new WindowBlock(ShellBlock.baseProperties().noOcclusion().isViewBlocking((s,l,p)->false)));
    public static final DeferredBlock<ControlBlock> TERMINAL=BLOCKS.register("archive_terminal",()->new ControlBlock(false,ShellBlock.baseProperties().noOcclusion().lightLevel(s->7)));
    public static final DeferredBlock<ControlBlock> EXIT=BLOCKS.register("archive_exit",()->new ControlBlock(true,ShellBlock.baseProperties().noOcclusion().lightLevel(s->10)));
    public static final DeferredItem<Item> CARTRIDGE=ITEMS.register("access_cartridge",()->new AccessCartridgeItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> TERMINAL_ITEM=ITEMS.registerSimpleBlockItem(TERMINAL);
    public static final DeferredItem<Item> EXPANDER=ITEMS.registerSimpleItem("spatial_expander");
    public static final DeferredItem<Item> ANNEX=ITEMS.registerSimpleItem("annex_module");
    public static final DeferredItem<Item> ANCHOR=ITEMS.registerSimpleItem("continuity_anchor",new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> RECALL_MODULE=ITEMS.registerSimpleItem("recall_module",new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> ARCHIVE_LINK=ITEMS.register("archive_link_upgrade",()->new ArchiveLinkItem(new Item.Properties()));
    public static final DeferredItem<Item> WAYPOINT_MODULE=ITEMS.registerSimpleItem("waypoint_module",new Item.Properties().stacksTo(1));
    public static final DeferredBlock<LiftPadBlock> LIFT_PAD=BLOCKS.register("lift_pad",()->new LiftPadBlock(ShellBlock.baseProperties().lightLevel(s->8)));
    public static final DeferredBlock<DaisBlock> WAYPOINT_DAIS=BLOCKS.register("waypoint_dais",()->new DaisBlock(ShellBlock.baseProperties().lightLevel(s->4)));
    public static final DeferredBlock<PedestalBlock> WAYPOINT_PEDESTAL=BLOCKS.register("waypoint_pedestal",()->new PedestalBlock(ShellBlock.baseProperties().lightLevel(s->5)));
    public static final DeferredBlock<LampPillarBlock> LAMP_PILLAR=BLOCKS.register("lamp_pillar",()->new LampPillarBlock(ShellBlock.baseProperties().noOcclusion().lightLevel(s->13)));
    public static final DeferredBlock<DestinationPadBlock> DESTINATION_PAD=BLOCKS.register("destination_pad",()->new DestinationPadBlock(BlockBehaviour.Properties.of().strength(2.0f,6.0f).isValidSpawn((s,l,p,e)->false).noOcclusion().lightLevel(s->s.getValue(DestinationPadBlock.BOUND)?8:2).sound(SoundType.COPPER)));
    public static final DeferredItem<Item> DESTINATION_PAD_ITEM=ITEMS.register("destination_pad",()->new DestinationPadItem(DESTINATION_PAD.get(),new Item.Properties()));
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,Nullspace.ID);
    public static final Supplier<BlockEntityType<DestinationPadBlockEntity>> DESTINATION_PAD_ENTITY=BLOCK_ENTITIES.register("destination_pad",()->BlockEntityType.Builder.of(DestinationPadBlockEntity::new,DESTINATION_PAD.get()).build(null));
    public static final DeferredItem<Item> APPLICATOR=ITEMS.register("surface_applicator",()->new SurfaceApplicatorItem(new Item.Properties().stacksTo(1)));
    static { TABS.register("workshop",()->CreativeModeTab.builder().title(Component.translatable("itemGroup.nullspace.workshop")).icon(()->CARTRIDGE.toStack()).displayItems((p,out)->{out.accept(CARTRIDGE);out.accept(TERMINAL_ITEM);out.accept(EXPANDER);out.accept(ANNEX);out.accept(ANCHOR);out.accept(RECALL_MODULE);out.accept(WAYPOINT_MODULE);out.accept(ARCHIVE_LINK);out.accept(DESTINATION_PAD_ITEM);out.accept(APPLICATOR);}).build()); }
    public static void register(IEventBus bus){BLOCKS.register(bus);BLOCK_ENTITIES.register(bus);ITEMS.register(bus);COMPONENTS.register(bus);SOUNDS.register(bus);TABS.register(bus);}
}
