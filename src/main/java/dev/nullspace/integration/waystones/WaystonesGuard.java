package dev.nullspace.integration.waystones;

import dev.nullspace.server.ArchiveService;
import dev.nullspace.text.Msg;
import net.blay09.mods.waystones.api.event.WaystoneTeleportEvent;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// soft integration with waystones (checked against 21.1.1, 21.1.20 and 21.1.46). TravelGuard only sees a waystone trip
// once the player is moved, after waystones took the xp and set the cooldown, and leashed mobs and pets are copied over
// without any travel event at all. waystones' Pre event comes before all of that, so a trip TravelGuard would refuse is
// cancelled there. balm posts its events on the neoforge bus. warp stones, scrolls, sharestones, warp plates and the
// inventory button all start with the same event. only loaded when waystones is present; any api mismatch switches it
// off with one error and TravelGuard stays the only guard.
public final class WaystonesGuard {
    private WaystonesGuard() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("nullspace");
    private static volatile boolean broken;

    public static void install(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, WaystonesGuard::onTeleport);
    }

    private static void onTeleport(WaystoneTeleportEvent.Pre event) {
        if (broken || event.isCanceled()) return;
        try {
            if (!(event.getContext().getEntity() instanceof ServerPlayer player)) return;
            var target = event.getContext().getTargetWaystone();
            var refusal = ArchiveService.foreignTravelRefusal(player, target.getDimension(), target.getPos());
            if (refusal == null) return;
            event.setCanceled(true);
            // waystones answers a cancelled trip with an empty action bar line of its own, so ours goes out after it
            var server = player.getServer();
            server.tell(new TickTask(server.getTickCount(), () -> Msg.refuse(player, refusal)));
        } catch (LinkageError | RuntimeException t) {
            if (broken) return;
            broken = true;
            LOGGER.error("Waystones is installed but its API does not match what Nullspace's Waystones guard was written against: the guard is switched OFF "
                + "for this run (refused waystone trips into archives then still cost their price, and leashed mobs can still be carried in). Please report the Waystones version.", t);
        }
    }
}
