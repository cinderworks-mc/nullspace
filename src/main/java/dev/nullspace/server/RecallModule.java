package dev.nullspace.server;

import dev.nullspace.content.Content;
import dev.nullspace.core.AccessControl;
import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.Permission;
import dev.nullspace.core.Recall;
import dev.nullspace.core.RecallPoint;
import dev.nullspace.core.ReturnLocation;
import dev.nullspace.core.Translatable;
import dev.nullspace.text.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RecallModule {
    private RecallModule() {}

    private static final Logger LOG = LoggerFactory.getLogger("nullspace");

    static void install(IEventBus bus) {
        // after every other mod has had its say: a death another mod cancelled (a revive mod, say) is not a death.
        bus.addListener(EventPriority.LOWEST, false, LivingDeathEvent.class, RecallModule::onDeath);
        bus.addListener(PlayerEvent.PlayerRespawnEvent.class, RecallModule::onRespawn);
        bus.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, RecallModule::onChangedDimension);
    }

    private static Archive ownArchive(ServerPlayer player) {
        Archive archive = ArchiveService.registryOf(player.getServer()).find(player.getUUID()).orElse(null);
        return archive != null && archive.initialized() ? archive : null;
    }

    private static boolean inside(Archive archive, ServerPlayer player) {
        return ArchiveService.DIMENSION.equals(player.level().dimension()) && archive.allocation().contains((int) Math.floor(player.getX()), (int) Math.floor(player.getZ()));
    }

    private static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Archive archive = ownArchive(player);
        if (archive == null) return;
        var policy = ArchiveService.currentPolicy();
        var where = new ReturnLocation(player.level().dimension().location().toString(), player.getX(), player.getY(), player.getZ(), player.getYRot(), 0f);
        // any archive dimension counts as "inside", not only the owner's own: dying in a friend's archive records nothing either.
        boolean insideAnyArchive = ArchiveService.DIMENSION.equals(player.level().dimension());
        if (Recall.recordDeath(archive, player.getUUID(), insideAnyArchive, where, ArchiveService.now(), policy.allowRecall())) {
            ArchiveService.dirtyData(player.getServer());
            LOG.info("Recall: {} died at {} in {}; remembered until they respawn in their archive", player.getGameProfile().getName(), player.blockPosition().toShortString(), where.dimension());
        }
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Archive archive = ownArchive(player);
        if (archive == null) return;
        RecallPoint before = archive.recallPoint();
        if (before == null) return;
        var policy = ArchiveService.currentPolicy();
        boolean armed = Recall.armOnRespawn(archive, player.getUUID(), inside(archive, player), ArchiveService.now(), policy.recallMinutes(), policy.allowRecall());
        ArchiveService.dirtyData(player.getServer());
        if (armed && before.armed()) {
            LOG.info("Recall: {} respawned in their archive again; the remembered spot is unchanged", player.getGameProfile().getName());
        } else if (armed) {
            LOG.info("Recall: {} respawned in their archive; the exit now leads to {} {}", player.getGameProfile().getName(), before.where().dimension(), before.where());
            // a gateway opened earlier still leads where it was opened to (the overworld spawn, say): close it so the next exit is computed afresh.
            ArchiveService.bridge().closeExits(player.getServer(), player.getUUID(), false);
            Msg.say(player, Msg.ok("message.nullspace.recall.armed", policy.recallMinutes()));
            Msg.say(player, Msg.hint("message.nullspace.recall.armed.hint"));
        } else {
            LOG.info("Recall: {} respawned and the remembered spot was dropped (not in their own archive, expired or switched off)", player.getGameProfile().getName());
        }
    }

    // leaving the archive uses up the spot; entering by another route (tempad, a command) ends it too
    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ArchiveService.DIMENSION.equals(event.getFrom()) && !ArchiveService.DIMENSION.equals(event.getTo())) return;
        if (ArchiveService.DIMENSION.equals(event.getFrom())) ArchiveService.bridge().closeExits(player.getServer(), player.getUUID(), true); // left by another route than the gateway
        Archive archive = ownArchive(player);
        if (archive != null && Recall.clear(archive, player.getUUID())) {
            ArchiveService.dirtyData(player.getServer());
            LOG.info("Recall: {} crossed {} -> {}; the remembered spot is cleared", player.getGameProfile().getName(), event.getFrom().location(), event.getTo().location());
        }
    }

    static ReturnLocation target(Archive archive, ServerPlayer player) {
        var policy = ArchiveService.currentPolicy();
        return Recall.target(archive, player.getUUID(), ArchiveService.now(), policy.recallMinutes(), policy.allowRecall()).orElse(null);
    }

    static void installFromTerminal(ServerPlayer player, BlockPos terminal, ItemStack stack) {
        Archive archive = ArchiveService.registryOf(player.getServer()).at(terminal.getX(), terminal.getZ()).orElse(null);
        String refusal = refusalKey(player, archive);
        if (refusal != null) { Msg.refuse(player, Msg.no(refusal)); return; }
        done(player, archive, stack);
    }

    static Component installFromInventory(ServerPlayer player) {
        Archive archive = ArchiveService.ownArchive(player);
        String refusal = refusalKey(player, archive);
        if (refusal != null) throw new ArchiveException(refusal);
        ItemStack stack = ItemStack.EMPTY;
        for (ItemStack candidate : player.getInventory().items) if (candidate.is(Content.RECALL_MODULE.get())) { stack = candidate; break; }
        if (stack.isEmpty() && player.getOffhandItem().is(Content.RECALL_MODULE.get())) stack = player.getOffhandItem();
        if (stack.isEmpty()) throw new ArchiveException("message.nullspace.recall.none");
        return done(player, archive, stack);
    }

    private static String refusalKey(ServerPlayer player, Archive archive) {
        if (archive == null || !AccessControl.isOwner(archive, player.getUUID())) return "message.nullspace.recall.owner_only";
        if (archive.recallInstalled()) return "message.nullspace.recall.already";
        if (!ArchiveService.currentPolicy().allowRecall()) return Translatable.disabled("recall");
        return null;
    }

    private static Component done(ServerPlayer player, Archive archive, ItemStack stack) {
        archive.installRecall(player.getUUID());
        if (!player.getAbilities().instabuild) stack.shrink(1);
        ArchiveService.dirtyData(player.getServer());
        ArchiveService.play(player, Content.TERMINAL_CONFIRM);
        Component message = Msg.ok("message.nullspace.recall.installed", ArchiveService.currentPolicy().recallMinutes());
        Msg.say(player, message);
        Msg.say(player, Msg.hint("message.nullspace.recall.installed.hint"));
        return message;
    }

    static boolean pointReady(Archive archive, long now, int minutes) {
        RecallPoint p = archive.recallPoint();
        return p != null && p.armed() && !Recall.expired(p, now, minutes);
    }
}
