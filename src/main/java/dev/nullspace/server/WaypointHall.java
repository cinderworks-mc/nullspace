package dev.nullspace.server;

import dev.nullspace.content.Content;
import dev.nullspace.core.AccessControl;
import dev.nullspace.core.ActionLimiter;
import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.Basement;
import dev.nullspace.core.Permission;
import dev.nullspace.core.Translatable;
import dev.nullspace.text.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WaypointHall {
    private WaypointHall() {}

    private static final Logger LOG = LoggerFactory.getLogger("nullspace");
    // installing builds about 800 blocks, so one try every few seconds is plenty and stops button mashing
    private static final ActionLimiter LIMITER = new ActionLimiter(8, 100);

    static void installFromTerminal(ServerPlayer player, BlockPos terminal, ItemStack stack) {
        Archive archive = ArchiveService.registryOf(player.getServer()).at(terminal.getX(), terminal.getZ()).orElse(null);
        try {
            Component message = install(player, archive, stack, player.getServer().getLevel(ArchiveService.DIMENSION));
            Msg.say(player, message);
            Msg.say(player, Msg.hint("message.nullspace.waypoint.installed.hint"));
        } catch (IllegalArgumentException | SecurityException e) {
            Msg.refuse(player, Msg.of(e));
        }
    }

    static Component installFromInventory(ServerPlayer player) {
        Archive archive = ArchiveService.ownArchive(player);
        ItemStack stack = ItemStack.EMPTY;
        for (ItemStack candidate : player.getInventory().items) if (candidate.is(Content.WAYPOINT_MODULE.get())) { stack = candidate; break; }
        if (stack.isEmpty() && player.getOffhandItem().is(Content.WAYPOINT_MODULE.get())) stack = player.getOffhandItem();
        if (stack.isEmpty() && !archive.basement().installed() && !player.getAbilities().instabuild) throw new ArchiveException("message.nullspace.waypoint.none");
        Component message = install(player, archive, stack, player.getServer().getLevel(ArchiveService.DIMENSION));
        Msg.say(player, message);
        Msg.say(player, Msg.hint("message.nullspace.waypoint.installed.hint"));
        return message;
    }

    static Component install(ServerPlayer player, Archive archive, ItemStack stack, ServerLevel level) {
        if (archive == null || !AccessControl.isOwner(archive, player.getUUID()))
            throw new ArchiveException("message.nullspace.waypoint.owner_only");
        if (archive.basement().installed()) throw new ArchiveException("message.nullspace.waypoint.already");
        if (!ArchiveService.hubPolicy().enabled()) throw new ArchiveException(Translatable.disabled("hall"));
        if (!archive.initialized()) throw new ArchiveException("error.nullspace.no_archive");
        if (level == null) throw new ArchiveException("error.nullspace.claim.no_dimension");
        // cold chunks: reading them below would load them inside this tick (about 120 ms measured). Ask for them and let the player try again.
        if (!HallBuilder.chunksLoaded(level, archive)) {
            RoomBuilder.prewarm(level, Basement.shell(archive.allocation()));
            LOG.debug("Waypoint Hall install for {} deferred: the hall's chunks are not loaded yet; they were requested", player.getGameProfile().getName());
            throw new ArchiveException("message.nullspace.waypoint.loading");
        }
        // after the still loading answer: waiting for chunks must not use up the player's tries
        if (!LIMITER.tryAcquire(player.getUUID(), player.getServer().getTickCount())) throw new ArchiveException("error.nullspace.slow_down");
        String blocked = HallBuilder.blocked(level, archive);
        if (blocked != null) throw new ArchiveException(blocked);
        long started = System.nanoTime();
        EditJournal.installHall(player.getServer(), level, archive);
        double ms = (System.nanoTime() - started) / 1_000_000.0;
        if (!player.getAbilities().instabuild && !stack.isEmpty()) stack.shrink(1);
        ArchiveService.dirtyData(player.getServer());
        ArchiveService.play(player, Content.ANCHOR_INSTALL);
        LOG.info("Waypoint Hall installed for {} ({} blocks in {} ms); journal clear", player.getGameProfile().getName(), Basement.shellBlockCount(), String.format("%.1f", ms));
        return Msg.ok("message.nullspace.waypoint.installed");
    }
}
