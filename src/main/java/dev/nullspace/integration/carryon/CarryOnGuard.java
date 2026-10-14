package dev.nullspace.integration.carryon;

import dev.nullspace.content.ControlBlock;
import dev.nullspace.core.RepeatedNotice;
import dev.nullspace.server.ArchiveService;
import dev.nullspace.server.AutomationGuard;
import dev.nullspace.text.Msg;
import earth.terrarium.tempad.api.event.TimedoorEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.TriState;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tschipp.carryon.common.carry.CarryOnData;
import tschipp.carryon.common.carry.CarryOnDataManager;
import tschipp.carryon.events.EntityPickupEvent;

// soft integration with carry on (checked against 2.2.2.11 to 2.2.6.13). the archive's own interact check already stops
// a guest, but carry on's cancelable EntityPickupEvent is the one hook that ignores listener order, so we ask there too.
// putting something down has no hook, so we cancel carry on's placement and stacking clicks first when the carrier
// lacks BUILD. tempad leaves a carried player behind and carry on then throws on the next click, so a gateway refuses
// a carrier of a player. only loaded when carryon is present; any api mismatch switches the guard off with one error.
public final class CarryOnGuard {
    private CarryOnGuard() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("nullspace");
    private static volatile boolean broken;
    // one message per window per player and gateway, since a refused carrier is refused every tick
    private static final RepeatedNotice GATEWAY_NOTICE = new RepeatedNotice(100);

    public static void install(IEventBus bus) {
        bus.addListener(EventPriority.HIGHEST, CarryOnGuard::onPickup);
        bus.addListener(EventPriority.HIGHEST, CarryOnGuard::onClickWhileCarrying);
        bus.addListener(EventPriority.HIGHEST, CarryOnGuard::onEntityClickWhileCarrying);
        bus.addListener(EventPriority.HIGH, CarryOnGuard::onGatewayEnter);
    }

    // every listener below catches here: a carry on api mismatch throws from inside its events and must not crash the game
    private static void broke(String where, Throwable t) {
        if (broken) return;
        broken = true;
        LOGGER.error("Carry On is installed but its API does not match what Nullspace's Carry On protection was written against ({}): the protection is switched OFF "
            + "for this run (visitors may then carry or put down things in archives without the archive's rights). Please report the Carry On version.", where, t);
    }

    private static void onPickup(EntityPickupEvent event) {
        if (broken) return;
        try {
            if (ArchiveService.entityPickupDenied(event.player, event.target)) event.setCanceled(true);
        } catch (LinkageError | RuntimeException t) { broke("entity pickup", t); }
    }

    // HIGHEST, so before carry on's own HIGH listener, which turns every block click into a placement. a click on the
    // exit or terminal while carrying is not a placement: the block is used here through the normal use code and the
    // event is cancelled so carry on does not see it. it needs the same right as a click without carrying (enter). any
    // other click while carrying an entity or player needs BUILD
    private static void onClickWhileCarrying(PlayerInteractEvent.RightClickBlock event) {
        if (broken) return;
        try {
            if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) return;
            var carry = CarryOnDataManager.getCarryData(player);
            if (!carry.isCarrying()) return;
            if (useControlBlock(event, player)) return;
            if (!carry.isCarrying(CarryOnData.CarryType.ENTITY) && !carry.isCarrying(CarryOnData.CarryType.PLAYER)) return;
            if (ArchiveService.carriedPlacementDenied(player, player.level(), event.getPos())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
            }
        } catch (LinkageError | RuntimeException t) { broke("block click while carrying", t); }
    }

    private static void onEntityClickWhileCarrying(PlayerInteractEvent.EntityInteract event) {
        if (broken) return;
        try {
            if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) return;
            var carry = CarryOnDataManager.getCarryData(player);
            if (!carry.isCarrying(CarryOnData.CarryType.ENTITY) && !carry.isCarrying(CarryOnData.CarryType.PLAYER)) return;
            if (ArchiveService.carriedPlacementDenied(player, player.level(), event.getTarget().blockPosition())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
            }
        } catch (LinkageError | RuntimeException t) { broke("entity click while carrying", t); }
    }

    private static void onGatewayEnter(TimedoorEvent.Enter event) {
        if (broken) return;
        try {
            if (!(event.getTeleportee() instanceof ServerPlayer player) || player.level().isClientSide()) return;
            var door = event.getEntity();
            if (!ArchiveService.DIMENSION.equals(door.level().dimension()) && !ArchiveService.DIMENSION.equals(door.getTargetDimension())) return;
            if (!CarryOnDataManager.getCarryData(player).isCarrying(CarryOnData.CarryType.PLAYER)) return;
            event.setCanceled(true);
            if (GATEWAY_NOTICE.mayTell(player.getUUID(), door.getUUID(), player.getServer().getTickCount()))
                player.displayClientMessage(Msg.no("message.nullspace.carry.no_gateway"), true);
        } catch (LinkageError | RuntimeException t) { broke("gateway crossing", t); }
    }

    private static boolean useControlBlock(PlayerInteractEvent.RightClickBlock event, ServerPlayer player) {
        var level = player.level();
        BlockState state = level.getBlockState(event.getPos());
        if (!(state.getBlock() instanceof ControlBlock) || !level.dimension().equals(ArchiveService.DIMENSION)) return false;
        if (!ArchiveService.mayDo(player, event.getPos(), AutomationGuard.controlBlockRequirement(event.getItemStack()))) return false;
        // what ServerPlayerGameMode#useItemOn does for a block: the held item first, then the bare block
        InteractionResult result = InteractionResult.PASS;
        var hit = event.getHitVec();
        if (!event.getItemStack().isEmpty()) {
            var item = state.useItemOn(event.getItemStack(), level, player, event.getHand(), hit);
            if (item.consumesAction()) result = item.result();
        }
        if (!result.consumesAction()) result = state.useWithoutItem(level, player, hit);
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        event.setCancellationResult(result.consumesAction() ? result : InteractionResult.CONSUME);
        return true;
    }
}
