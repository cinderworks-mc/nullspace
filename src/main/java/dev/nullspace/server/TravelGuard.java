package dev.nullspace.server;

import dev.nullspace.core.AccessControl;
import dev.nullspace.core.Archive;
import dev.nullspace.text.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

// other mods (waystones, commands) can move players across dimensions without tempad. entering the archive that way
// is refused unless a tempad crossing recorded a visit (or the player is an operator), and leaving by any route ends
// the visit record
final class TravelGuard {
    private TravelGuard() {}

    static void install(IEventBus bus) {
        bus.addListener(TravelGuard::onTravel);
        bus.addListener(TravelGuard::onChanged);
        bus.addListener(TravelGuard::onRespawn);
    }

    // a visit only exists while the player is in the archive dimension. dying there and respawning elsewhere moves
    // the player without a dimension change event, so stale visits are dropped on respawn and login
    static void dropStaleVisits(ServerPlayer player) {
        if (ArchiveService.DIMENSION.equals(player.level().dimension())) return;
        boolean changed = false;
        for (Archive a : ArchiveService.registryOf(player.getServer()).archives().values())
            changed |= AccessControl.exit(a, player.getUUID()).isPresent();
        if (changed) ArchiveService.dirtyData(player.getServer());
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) dropStaleVisits(player);
    }

    private static boolean visiting(ServerPlayer player) {
        for (Archive a : ArchiveService.registryOf(player.getServer()).archives().values()) if (a.activeVisits().containsKey(player.getUUID())) return true;
        return false;
    }

    static volatile boolean bypassed;

    private static void onTravel(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !ArchiveService.DIMENSION.equals(event.getDimension())) return;
        if (player.hasPermissions(2) || bypassed || visiting(player)) return;
        event.setCanceled(true);
        Msg.refuse(player, Msg.no("message.nullspace.travel.gateway_only"));
    }

    // the same rules for a teleport mod that asks before it moves anyone and charges for the trip (waystones). a hop
    // inside the archive dimension has no travel event, so the target archive is checked too: the stranger sweep
    // would send the player out again a second later
    static Component refusal(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to, BlockPos at) {
        if (!ArchiveService.DIMENSION.equals(to) || player.hasPermissions(2) || bypassed) return null;
        boolean entering = !ArchiveService.DIMENSION.equals(from);
        Archive there = ArchiveService.registryOf(player.getServer()).at(at.getX(), at.getZ()).orElse(null);
        if (entering && !visiting(player) || !AccessControl.mayStayAt(there, player.getUUID(), ArchiveService.now(), ArchiveService.currentPolicy()))
            return Msg.no("message.nullspace.travel.gateway_only");
        return null;
    }

    private static void onChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !ArchiveService.DIMENSION.equals(event.getFrom())) return;
        endVisits(player);
    }

    static void endVisits(ServerPlayer player) {
        boolean changed = false;
        for (Archive a : ArchiveService.registryOf(player.getServer()).archives().values())
            changed |= AccessControl.exit(a, player.getUUID()).isPresent();
        if (changed) ArchiveService.dirtyData(player.getServer());
    }
}
