package dev.nullspace.server;

import dev.nullspace.content.ControlBlock;
import dev.nullspace.content.Content;
import dev.nullspace.content.DestinationPadBlock;
import dev.nullspace.content.HallBlock;
import dev.nullspace.content.ShellBlock;
import dev.nullspace.core.ModParts;
import dev.nullspace.core.Permission;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.Collection;

// indirect access: things that move items, fluids or energy without a player opening anything. server side and
// independent of menus. packs extend it with the block tag nullspace:automation and the item tag
// nullspace:automation_items
public final class AutomationGuard {
    private AutomationGuard() {}

    public static final TagKey<Block> AUTOMATION_BLOCKS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("nullspace", "automation"));
    public static final TagKey<Item> AUTOMATION_ITEMS = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("nullspace", "automation_items"));
    // priority of the protection listeners: before other mods' normal priority handlers can act
    public static final EventPriority GUARD_PRIORITY = EventPriority.HIGHEST;
    private static final Direction[] SIDES = Direction.values();

    public static boolean structural(BlockState state) {
        Block block = state.getBlock();
        return block instanceof ShellBlock || block == Content.WINDOW.get() || block instanceof ControlBlock || block instanceof HallBlock;
    }

    public static boolean exposesHandler(Level level, BlockPos pos) {
        if (probe(level, pos, null)) return true;
        for (Direction side : SIDES) if (probe(level, pos, side)) return true;
        return false;
    }

    private static boolean probe(Level level, BlockPos pos, Direction side) {
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side) != null
            || level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side) != null
            || level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) != null;
    }

    public static boolean storageLike(Level level, BlockPos pos) {
        var entity = level.getBlockEntity(pos);
        return entity != null && (entity instanceof Container || exposesHandler(level, pos));
    }

    public static boolean transport(Level level, BlockPos pos, BlockState state) {
        if (state.is(AUTOMATION_BLOCKS)) return true;
        var entity = level.getBlockEntity(pos);
        if (entity == null) return false;
        if (entity instanceof Container || exposesHandler(level, pos)) return true;
        return ServerConfig.FAIL_CLOSED_AUTOMATION.get() && !"minecraft".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
    }

    // a transport block next to a storage block needs STORAGE, so a BUILD guest cannot put a hopper under or a pipe
    // beside a barrel. placing it away from storage is plain BUILD
    public static boolean placementDenied(ServerPlayer player, Level level, BlockPos pos, BlockState placed) {
        if (ArchiveService.mayDo(player, pos, Permission.STORAGE)) return false;
        if (!transport(level, pos, placed)) return false;
        for (Direction side : SIDES) if (storageLike(level, pos.relative(side))) return true;
        return false;
    }

    // only the owner or an operator (ADMIN) places pads, so only they break them: BUILD and STORAGE must not remove
    // the owner's pads
    public static boolean padBreakDenied(ServerPlayer player, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof DestinationPadBlock && !ArchiveService.mayDo(player, pos, Permission.ADMIN);
    }

    public static boolean breakDenied(ServerPlayer player, Level level, BlockPos pos) {
        if (ArchiveService.mayDo(player, pos, Permission.STORAGE)) return false;
        if (storageLike(level, pos)) return true;
        // a modded block entity may hold contents without a standard container or capability; treat it like storage
        // to be safe
        var entity = level.getBlockEntity(pos);
        return entity != null && ServerConfig.FAIL_CLOSED_AUTOMATION.get()
            && !"minecraft".equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getNamespace());
    }

    public static Permission[] itemRequirement(ItemStack stack) {
        var item = stack.getItem();
        boolean placer = item instanceof BucketItem || item instanceof SpawnEggItem || item instanceof MinecartItem || item instanceof BoatItem
            || item instanceof ArmorStandItem || item instanceof HangingEntityItem || item instanceof FlintAndSteelItem || item instanceof FireChargeItem
            || item instanceof ThrowablePotionItem || item instanceof EndCrystalItem;
        if (stack.is(AUTOMATION_ITEMS)) return new Permission[] {Permission.BUILD, Permission.STORAGE};
        // parts (AE2 cables, buses, planes, panels) are added to an existing cable, or make a cable bus of their own, without a block item or placement event: they are automation like the tagged items.
        var kind = ModParts.kind(item.getClass());
        if (kind == ModParts.Kind.PART) return new Permission[] {Permission.BUILD, Permission.STORAGE};
        return placer || kind == ModParts.Kind.FACADE ? new Permission[] {Permission.BUILD} : new Permission[0];
    }

    public static boolean pistonMovesProtected(Level level, Collection<BlockPos> moved) {
        for (BlockPos p : moved) if (structural(level.getBlockState(p)) || level.getBlockEntity(p) != null) return true;
        return false;
    }

    public static boolean furniture(Entity entity) {
        return entity instanceof HangingEntity || entity instanceof ArmorStand || entity instanceof Boat || entity instanceof AbstractMinecart;
    }

    public static Permission[] controlBlockRequirement(ItemStack stack) {
        Permission[] item = itemRequirement(stack);
        Permission[] all = new Permission[item.length + 1];
        all[0] = Permission.ENTER;
        System.arraycopy(item, 0, all, 1, item.length);
        return all;
    }

    // a bed is a station (INTERACT_BLOCK) but also how SET_SPAWN is used (sleep in a bed here to set your spawn)
    public static boolean mayUseBed(ServerPlayer player, BlockPos pos) {
        return ArchiveService.mayDo(player, pos, Permission.SET_SPAWN) || ArchiveService.mayDo(player, pos, Permission.INTERACT_BLOCK);
    }

    // an entity that holds items: breaking it drops them and using it opens them, so INTERACT_ENTITY alone is not
    // enough
    public static boolean holdsItems(Entity entity) {
        return entity instanceof Container || entity instanceof ContainerEntity
            || (entity instanceof AbstractChestedHorse horse && horse.hasChest());
    }

    // a block visitors may use with entry alone because it is a grave (the grave mod decides whose): a block of a
    // grave mod that holds items. their decorative blocks are ordinary
    public static boolean graveBlock(ResourceLocation id, boolean hasBlockEntity) {
        return hasBlockEntity && DeathAndSpawn.GRAVE_MODS.contains(id.getNamespace());
    }

    // an entity visitors may use with entry alone because it is a grave marker (corpse's corpse). other entities of
    // those mods are ordinary mobs
    public static boolean graveEntityType(ResourceLocation id) {
        return id.getNamespace().equals("corpse") && id.getPath().equals("corpse");
    }

    public static boolean graveEntity(Entity entity) { return graveEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())); }

    public static boolean graveBlock(Level level, BlockPos pos, BlockState state) {
        return graveBlock(BuiltInRegistries.BLOCK.getKey(state.getBlock()), level.getBlockEntity(pos) != null);
    }

    public static Permission[] entityRequirement(Entity entity) {
        if (graveEntity(entity)) return new Permission[] {Permission.ENTER};
        return holdsItems(entity) ? new Permission[] {Permission.INTERACT_ENTITY, Permission.STORAGE} : new Permission[] {Permission.INTERACT_ENTITY};
    }

    public static boolean hurtDenied(Entity target, DamageSource source) {
        if (!(source.getEntity() instanceof ServerPlayer p) || p == target) return false;
        return !ArchiveService.mayDo(p, target.blockPosition(), entityRequirement(target));
    }

    public static Permission[] blockUseRequirement(ItemStack stack) {
        var item = stack.getItem();
        boolean changesBlocks = item instanceof DiggerItem || item instanceof BoneMealItem
            || item instanceof ShearsItem || item instanceof HoneycombItem || item instanceof BrushItem;
        return changesBlocks ? new Permission[] {Permission.BUILD} : new Permission[0];
    }
}
