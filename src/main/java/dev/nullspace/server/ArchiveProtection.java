package dev.nullspace.server;

import dev.nullspace.Nullspace;
import dev.nullspace.content.ControlBlock;
import dev.nullspace.content.DestinationPadBlock;
import dev.nullspace.content.ShellBlock;
import dev.nullspace.content.Content;
import dev.nullspace.core.AccessControl;
import dev.nullspace.core.CarryRules;
import dev.nullspace.core.Permission;
import dev.nullspace.text.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

import java.util.stream.Stream;

// server side enforcement, menu state is not trusted. block break, place and bucket use need BUILD; blocks with a
// block entity STORAGE (plus INTERACT_BLOCK for modded blocks when conservative); other blocks INTERACT_BLOCK;
// entities INTERACT_ENTITY. operators (level 2) bypass. shell and control blocks only break for operators
final class ArchiveProtection {
    private ArchiveProtection() {}

    static void install(IEventBus bus) {
        // Player-action guards run first: other mods (Functional Storage drawers take items on LeftClickBlock) act on the same events at normal
        // priority, and an event they handle before we cancel it has already done its work. Cancelled events are not delivered to them.
        var first = AutomationGuard.GUARD_PRIORITY;
        bus.addListener(first, ArchiveProtection::onBreak);
        bus.addListener(first, ArchiveProtection::onPlace);
        bus.addListener(first, ArchiveProtection::onRightClickBlock);
        bus.addListener(first, ArchiveProtection::onLeftClickBlock);
        bus.addListener(first, ArchiveProtection::onEntityInteract);
        bus.addListener(first, ArchiveProtection::onEntitySpecific);
        bus.addListener(first, ArchiveProtection::onAttack);
        bus.addListener(first, ArchiveProtection::onPickup);
        bus.addListener(first, ArchiveProtection::onRightClickItem);
        bus.addListener(ArchiveProtection::onExplosion);
        bus.addListener(ArchiveProtection::onPiston);
        bus.addListener(ArchiveProtection::onFluidPlace);
        bus.addListener(ArchiveProtection::onInvulnerable);
    }

    private static final TagKey<Block> STORAGE_BLOCKS =
        TagKey.create(Registries.BLOCK, Nullspace.id("storage"));

    private static boolean inArchive(Level level) { return !level.isClientSide() && level.dimension().equals(ArchiveService.DIMENSION); }

    private static boolean structural(BlockState state) { return AutomationGuard.structural(state); }

    private static void deny(ServerPlayer player) {
        player.displayClientMessage(Msg.no("message.nullspace.denied.any"), true);
    }

    private static void deny(ServerPlayer player, Permission missing) {
        player.displayClientMessage(Msg.denied(missing), true);
    }

    private static Permission firstMissing(ServerPlayer player, BlockPos pos, Permission... needed) {
        for (Permission p : needed) if (!ArchiveService.mayDo(player, pos, p)) return p;
        return needed.length > 0 ? needed[0] : Permission.BUILD;
    }

    private static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer player) || !inArchive(player.level())) return;
        if (AutomationGuard.graveBlock(player.level(), e.getPos(), e.getState())) {
            // some grave mods (GraveStone) hand items back when the owner breaks the grave; the mod itself checks whose grave it is.
            if (!ArchiveService.mayDo(player, e.getPos(), Permission.ENTER)) { e.setCanceled(true); deny(player, Permission.ENTER); }
            return;
        }
        boolean ok = ArchiveService.mayDo(player, e.getPos(), Permission.BUILD) && (!structural(e.getState()) || player.hasPermissions(2) || movableControl(player, e.getPos(), e.getState()))
            && !AutomationGuard.breakDenied(player, player.level(), e.getPos()) && !AutomationGuard.padBreakDenied(player, e.getPos(), e.getState());
        if (!ok) { e.setCanceled(true); deny(player, Permission.BUILD); }
    }

    // owner can pick the room's terminal and exit up and move them. the waypoint hall's own stay fixed, the exit there is two tall
    private static boolean movableControl(ServerPlayer player, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ControlBlock)) return false;
        var archive = ArchiveService.registryOf(player.getServer()).at(pos.getX(), pos.getZ()).orElse(null);
        if (archive == null || !ArchiveService.mayDo(player, pos, Permission.ADMIN)) return false;
        var terminal = dev.nullspace.core.Basement.terminal(archive.allocation());
        var exit = dev.nullspace.core.Basement.exit(archive.allocation());
        boolean hall = (pos.getX() == terminal.x() && pos.getY() == terminal.y() && pos.getZ() == terminal.z())
            || (pos.getX() == exit.x() && (pos.getY() == exit.y() || pos.getY() == exit.y() + 1) && pos.getZ() == exit.z());
        return !hall;
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player) || !(e.getLevel() instanceof Level level) || !inArchive(level)) return;
        if (!ArchiveService.mayDo(player, e.getPos(), Permission.BUILD) || AutomationGuard.placementDenied(player, level, e.getPos(), e.getPlacedBlock())) { e.setCanceled(true); deny(player, ArchiveService.mayDo(player, e.getPos(), Permission.BUILD) ? Permission.STORAGE : Permission.BUILD); return; }
        if (e.getPlacedBlock().getBlock() instanceof ControlBlock && !ArchiveService.mayDo(player, e.getPos(), Permission.ADMIN)) { e.setCanceled(true); deny(player, Permission.ADMIN); return; }
        // The Waypoint Hall's rules: Destination Pads only on the twelve slots, only for the owner, nothing in the lift column or on a slot.
        String hall = HubPads.placementRefusal(player, ArchiveService.registryOf(player.getServer()).at(e.getPos().getX(), e.getPos().getZ()).orElse(null), e.getPos(),
            e.getPlacedBlock().is(Content.DESTINATION_PAD.get()));
        if (hall != null) { e.setCanceled(true); player.displayClientMessage(Msg.no(hall), true); }
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer player) || !inArchive(player.level())) return;
        BlockPos pos = e.getPos();
        BlockState state = player.level().getBlockState(pos);
        if (AutomationGuard.graveBlock(player.level(), pos, state)) {
            // grave mods enforce their own ownership; visitors must still be allowed to recover what they died with.
            Permission[] need = AutomationGuard.controlBlockRequirement(e.getItemStack());
            if (!ArchiveService.mayDo(player, pos, need)) cancel(e, player, firstMissing(player, pos, need));
            return;
        }
        if (state.getBlock() instanceof DestinationPadBlock) {
            // using a pad is for the owner and operators; the block checks the rest. Anyone else is told so (not "you lack storage rights").
            if (!ArchiveService.mayDo(player, pos, Permission.ADMIN)) {
                e.setUseBlock(TriState.FALSE); e.setUseItem(TriState.FALSE); e.setCanceled(true); e.setCancellationResult(InteractionResult.FAIL);
                player.displayClientMessage(Msg.no("message.nullspace.pad.owner_only"), true);
            }
            return;
        }
        if (state.getBlock() instanceof ControlBlock) {
            Permission[] need = AutomationGuard.controlBlockRequirement(e.getItemStack());
            if (!ArchiveService.mayDo(player, pos, need)) cancel(e, player, firstMissing(player, pos, need));
            return;
        }
        // The Finish Brush checks BUILD itself (and copying a finish is read-only); the shell is not "used", so do not demand INTERACT_BLOCK for it.
        if (e.getItemStack().is(Content.APPLICATOR.get()) && structural(state)) return;
        boolean placing = !e.getItemStack().isEmpty() && (e.getItemStack().getItem() instanceof BlockItem || e.getItemStack().getItem() instanceof BucketItem);
        boolean ok = true;
        BlockEntity entity = player.level().getBlockEntity(pos);
        if (entity != null) {
            boolean vanilla = "minecraft".equals(state.getBlock().builtInRegistryHolder().key().location().getNamespace());
            boolean storage = state.is(STORAGE_BLOCKS);
            if (state.getBlock() instanceof BedBlock) ok = AutomationGuard.mayUseBed(player, pos);
            else if (vanilla && !storage) ok = ArchiveService.mayDo(player, pos, Permission.INTERACT_BLOCK); // furnaces, enchanting tables...: stations, not storage
            else {
                ok = ArchiveService.mayDo(player, pos, Permission.STORAGE);
                if (ok && !vanilla && ServerConfig.FAIL_CLOSED_AUTOMATION.get()) ok = ArchiveService.mayDo(player, pos, Permission.INTERACT_BLOCK);
            }
        } else if (!state.isAir()) {
            ok = ArchiveService.mayDo(player, pos, Permission.INTERACT_BLOCK);
        }
        if (!ok) {
            // placing against a block the guest may not use is still plain BUILD: suppress the block's own use, keep the item.
            if (placing && ArchiveService.mayDo(player, pos, Permission.BUILD)) { e.setUseBlock(TriState.FALSE); ok = true; }
            else { cancel(e, player, entity != null ? firstMissing(player, pos, Permission.STORAGE, Permission.INTERACT_BLOCK) : Permission.INTERACT_BLOCK); return; }
        }
        if (placing && !ArchiveService.mayDo(player, pos, Permission.BUILD)) {
            // a held block must not stop a permitted use of the block clicked (a furnace with a Tempad in hand): only the placement is refused.
            e.setUseItem(TriState.FALSE);
            deny(player, Permission.BUILD);
            return;
        }
        Permission[] needed = AutomationGuard.itemRequirement(e.getItemStack());
        if (needed.length > 0 && !ArchiveService.mayDo(player, pos, needed)) { cancel(e, player, firstMissing(player, pos, needed)); return; }
        Permission[] tool = AutomationGuard.blockUseRequirement(e.getItemStack());
        if (tool.length > 0 && !ArchiveService.mayDo(player, pos, tool)) {
            // the block may still be used (a door, a button); only the item's own effect on the block is suppressed.
            e.setUseItem(TriState.FALSE);
            deny(player, firstMissing(player, pos, tool));
        }
    }

    private static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (!(e.getEntity() instanceof ServerPlayer player) || !inArchive(player.level())) return;
        Permission[] needed = AutomationGuard.itemRequirement(e.getItemStack());
        if (needed.length == 0 || ArchiveService.mayDo(player, player.blockPosition(), needed)) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.FAIL);
        deny(player, firstMissing(player, player.blockPosition(), needed));
    }

    private static void onExplosion(ExplosionEvent.Detonate e) {
        if (!inArchive(e.getLevel())) return;
        e.getAffectedBlocks().removeIf(p -> structural(e.getLevel().getBlockState(p)) || e.getLevel().getBlockEntity(p) != null);
        e.getAffectedEntities().removeIf(AutomationGuard::furniture);
    }

    // pistons do not move the shell or blocks with contents. vanilla refuses block entities already; this covers
    // modded pistons and storage without a block entity
    private static void onPiston(PistonEvent.Pre e) {
        if (!pistonGuardEnabled || !(e.getLevel() instanceof Level level) || !inArchive(level)) return;
        var resolver = e.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;
        if (AutomationGuard.pistonMovesProtected(level, Stream.concat(resolver.getToPush().stream(), resolver.getToDestroy().stream()).toList())) e.setCanceled(true);
    }

    static volatile boolean pistonGuardEnabled = true;

    private static void onFluidPlace(BlockEvent.FluidPlaceBlockEvent e) {
        if (!(e.getLevel() instanceof Level level) || !inArchive(level)) return;
        if (structural(level.getBlockState(e.getPos()))) e.setCanceled(true);
    }

    private static void onInvulnerable(EntityInvulnerabilityCheckEvent e) {
        Entity target = e.getEntity();
        if (target.level().isClientSide() || !inArchive(target.level())) return;
        if (AutomationGuard.hurtDenied(target, e.getSource())) e.setInvulnerable(true);
    }

    private static void cancel(PlayerInteractEvent.RightClickBlock e, ServerPlayer player, Permission missing) {
        e.setUseBlock(TriState.FALSE);
        e.setUseItem(TriState.FALSE);
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.FAIL);
        deny(player, missing);
    }

    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer player) || !inArchive(player.level())) return;
        if (AutomationGuard.graveBlock(player.level(), e.getPos(), player.level().getBlockState(e.getPos()))) {
            if (!ArchiveService.mayDo(player, e.getPos(), Permission.ENTER)) { e.setCanceled(true); deny(player, Permission.ENTER); }
            return;
        }
        if (!ArchiveService.mayDo(player, e.getPos(), Permission.BUILD)) {
            e.setCanceled(true);
            deny(player, Permission.BUILD);
            return;
        }
        // some storage mods hand out contents on a left-click (Functional Storage drawers): a block that holds contents needs STORAGE for that too.
        if (AutomationGuard.breakDenied(player, player.level(), e.getPos())) {
            e.setCanceled(true);
            deny(player, Permission.STORAGE);
        }
    }

    private static void entity(Entity target, ServerPlayer player, Runnable cancel) {
        if (!inArchive(player.level())) return;
        Permission[] need = AutomationGuard.entityRequirement(target);
        if (ArchiveService.mayDo(player, target.blockPosition(), need)) return;
        cancel.run();
        deny(player, firstMissing(player, target.blockPosition(), need));
    }

    // entity pickup by other mods (carry on) can move an entity out of the archive without an interact check of its
    // own. true, after telling the player, when they may not take the target. same rights as using it
    static boolean entityPickupDenied(ServerPlayer player, Entity target) {
        if (!inArchive(target.level())) return false;
        if (target instanceof ServerPlayer victim && ownerCarryDenied(player, victim)) {
            player.displayClientMessage(Msg.no("message.nullspace.carry.no_owner"), true);
            return true;
        }
        Permission[] need = AutomationGuard.entityRequirement(target);
        if (ArchiveService.mayDo(player, target.blockPosition(), need)) return false;
        deny(player, firstMissing(player, target.blockPosition(), need));
        return true;
    }

    static volatile boolean ownerRuleOff;

    static boolean ownerCarryDenied(ServerPlayer carrier, ServerPlayer victim) {
        if (ownerRuleOff) return false;
        var archive = ArchiveService.registryOf(carrier.getServer()).at(victim.getBlockX(), victim.getBlockZ()).orElse(null);
        return archive != null && CarryRules.ownerMayNotBeCarried(archive.owner(), victim.getUUID(), carrier.getUUID(), carrier.hasPermissions(2));
    }

    static boolean carriedPlacementDenied(ServerPlayer player, Level level, BlockPos pos) {
        if (!inArchive(level) || ArchiveService.mayDo(player, pos, Permission.BUILD)) return false;
        player.displayClientMessage(Msg.no("message.nullspace.carry.no_build", Component.translatable(Msg.permissionKey(Permission.BUILD))), true);
        return true;
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getEntity() instanceof ServerPlayer p) entity(e.getTarget(), p, () -> { e.setCanceled(true); e.setCancellationResult(InteractionResult.FAIL); });
    }

    private static void onEntitySpecific(PlayerInteractEvent.EntityInteractSpecific e) {
        if (e.getEntity() instanceof ServerPlayer p) entity(e.getTarget(), p, () -> { e.setCanceled(true); e.setCancellationResult(InteractionResult.FAIL); });
    }

    // someone standing in an archive they have no place in (it is swept out within a second) takes nothing from the floor
    private static void onPickup(ItemEntityPickupEvent.Pre e) {
        if (!(e.getPlayer() instanceof ServerPlayer p) || !inArchive(p.level()) || p.hasPermissions(2)) return;
        var here = ArchiveService.registryOf(p.getServer()).at((int) Math.floor(p.getX()), (int) Math.floor(p.getZ())).orElse(null);
        if (!AccessControl.mayStayAt(here, p.getUUID(), ArchiveService.now(), ArchiveService.currentPolicy())) e.setCanPickup(TriState.FALSE);
    }

    private static void onAttack(AttackEntityEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) entity(e.getTarget(), p, () -> e.setCanceled(true));
    }
}
