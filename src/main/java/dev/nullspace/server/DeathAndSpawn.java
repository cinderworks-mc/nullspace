package dev.nullspace.server;

import dev.nullspace.core.AccessControl;
import dev.nullspace.core.Archive;
import dev.nullspace.text.Msg;
import dev.nullspace.core.Permission;
import dev.nullspace.core.ReturnLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// death, xp and spawn rules inside archives, vanilla by default. retention needs the owner's opt in, server policy to
// allow it and no known grave mod (it would store the same items)
final class DeathAndSpawn {
    private DeathAndSpawn() {}

    // mod ids read from the released 1.21.1 jars of gravestone, you're in grave danger, corpse and similar
    static final List<String> GRAVE_MODS = List.of("gravestone", "yigd", "corpse", "tombstone");

    static boolean graveModLoaded() { return GRAVE_MODS.stream().anyMatch(ModList.get()::isLoaded); }

    static void install(IEventBus bus) {
        // last and without cancelled deaths, so only a death that really happens leaves a snapshot (see RecallModule)
        bus.addListener(EventPriority.LOWEST, false, LivingDeathEvent.class, DeathAndSpawn::onDeath);
        bus.addListener(DeathAndSpawn::onDrops);
        bus.addListener(DeathAndSpawn::onXp);
        bus.addListener(DeathAndSpawn::onRespawn);
        bus.addListener(DeathAndSpawn::onSetSpawn);
    }

    private static Archive deathArchive(ServerPlayer player) {
        if (!ArchiveService.DIMENSION.equals(player.level().dimension())) return null;
        return ArchiveService.registryOf(player.getServer()).at((int) Math.floor(player.getX()), (int) Math.floor(player.getZ())).orElse(null);
    }

    private static boolean keepInventory(Archive archive) {
        return archive != null && !graveModLoaded() && ArchiveService.currentPolicy().effective(archive.settings()).keepInventory();
    }

    private static boolean keepXp(Archive archive) {
        return archive != null && !graveModLoaded() && ArchiveService.currentPolicy().effective(archive.settings()).keepXp();
    }

    private static CompoundTag kept(MinecraftServer server, ServerPlayer player) {
        return ArchiveSavedData.get(server.overworld()).keptDeaths().computeIfAbsent(player.getUUID(), id -> new CompoundTag());
    }

    private record Held(long tick, ListTag saved, List<ItemStack> stacks) {}
    private static final Map<UUID, Held> HELD = new HashMap<>();

    // all slots, so armor and the offhand come back where they were, like vanilla keepInventory
    private static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !keepInventory(deathArchive(player))) return;
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (!player.getInventory().getItem(i).isEmpty()) stacks.add(player.getInventory().getItem(i));
        HELD.put(player.getUUID(), new Held(player.serverLevel().getGameTime(), player.getInventory().save(new ListTag()), stacks));
    }

    // drops only come after a death that stood, so this is where the snapshot is kept. Only the stacks the snapshot holds are taken out of the drops:
    // whatever other mods added (curios slots, say) falls as usual.
    private static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Held held = HELD.remove(player.getUUID());
        var covered = held == null || held.tick() != player.serverLevel().getGameTime() || !keepInventory(deathArchive(player)) ? null
            : event.getDrops().stream().filter(drop -> held.stacks().stream().anyMatch(stack -> stack == drop.getItem())).toList();
        // a stack that is not among the drops (kept by the game rule, or swapped by another mod) cannot be both restored and dropped: let the drops happen rather than risk a copy
        if (covered == null || covered.isEmpty() || covered.size() != held.stacks().size()) {
            var old = ArchiveSavedData.get(player.getServer().overworld()).keptDeaths().get(player.getUUID());
            if (old != null && old.contains("inventory")) { old.remove("inventory"); ArchiveService.dirtyData(player.getServer()); }
            return;
        }
        event.getDrops().removeAll(covered);
        kept(player.getServer(), player).put("inventory", held.saved());
        ArchiveService.dirtyData(player.getServer());
    }

    private static void onXp(LivingExperienceDropEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !keepXp(deathArchive(player))) return;
        // the same three values vanilla keepInventory copies. totalExperience alone can be 0 for players whose level was set directly (e.g. /xp add ... levels).
        var kept = kept(player.getServer(), player);
        kept.putInt("xpLevel", player.experienceLevel); kept.putFloat("xpProgress", player.experienceProgress); kept.putInt("xpTotal", player.totalExperience);
        ArchiveService.dirtyData(player.getServer());
        event.setDroppedExperience(0);
        event.setCanceled(true);
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered() || !(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        ArchiveSavedData data = ArchiveSavedData.get(server.overworld());
        CompoundTag saved = data.keptDeaths().remove(player.getUUID());
        if (saved != null) {
            data.setDirty();
            if (saved.contains("inventory")) {
                player.getInventory().load(saved.getList("inventory", 10));
                Msg.say(player, Msg.ok("message.nullspace.retained.items"));
            }
            if (saved.getInt("xpLevel") > 0 || saved.getFloat("xpProgress") > 0 || saved.getInt("xpTotal") > 0) {
                player.experienceLevel = saved.getInt("xpLevel"); player.experienceProgress = saved.getFloat("xpProgress"); player.totalExperience = saved.getInt("xpTotal");
                player.connection.send(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
                Msg.say(player, Msg.ok("message.nullspace.retained.xp"));
            }
        }
        // dying inside an archive and respawning elsewhere is not a dimension-change event; the visit must still end.
        if (!ArchiveService.DIMENSION.equals(player.level().dimension())) { TravelGuard.endVisits(player); return; }
        Archive archive = deathArchive(player);
        if (archive == null && ArchiveService.DIMENSION.equals(player.level().dimension()) && !player.hasPermissions(2)) {
            ArchiveService.evictToFallback(player);
            return;
        }
        if (archive == null || player.hasPermissions(2)) return;
        // standing in an archive after a respawn means a stored respawn point inside it. SET_SPAWN (and the
        // server/archive spawn veto) gates that point the way it gated setting it: a right revoked or a veto switched
        // on since then must not pull the player in again (once)
        long now = ArchiveService.now();
        boolean owner = archive.owner().equals(player.getUUID());
        if (!owner && !archive.activeVisits().containsKey(player.getUUID())) {
            // a guest who died elsewhere (typically the overworld) with the point inside the archive has no visit yet: the invitation must grant
            // entry and SET_SPAWN (and no veto may be on); the respawn then starts a visit whose return point is the world spawn.
            if (!AccessControl.invitedSpawnRight(archive, player.getUUID(), ArchiveService.currentPolicy()) || !AccessControl.mayEnter(archive, player.getUUID(), now, ArchiveService.currentPolicy())) {
                ArchiveService.evictToFallback(player);
            } else {
                ServerPlayerReturn.recordRespawnVisit(server, archive, player, now);
            }
            return;
        }
        if (!ArchiveService.mayDo(player, player.blockPosition(), Permission.SET_SPAWN)) ArchiveService.evictToFallback(player);
    }

    // vanilla copies the stored respawn point to the new player before a guest who died elsewhere has a visit. that
    // copy must not be vetoed when the invitation grants entry and SET_SPAWN (nobody can sleep in an archive without
    // a visit, so no new way to set a point)
    private static boolean invitedWithoutVisit(ServerPlayer player, BlockPos pos) {
        Archive archive = ArchiveService.registryOf(player.getServer()).at(pos.getX(), pos.getZ()).orElse(null);
        if (archive == null || archive.owner().equals(player.getUUID()) || archive.activeVisits().containsKey(player.getUUID())) return false;
        return AccessControl.invitedSpawnRight(archive, player.getUUID(), ArchiveService.currentPolicy()) && AccessControl.mayEnter(archive, player.getUUID(), ArchiveService.now(), ArchiveService.currentPolicy());
    }

    private static void onSetSpawn(PlayerSetSpawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !ArchiveService.DIMENSION.equals(event.getSpawnLevel()) || event.getNewSpawn() == null) return;
        if (!ArchiveService.mayDo(player, event.getNewSpawn(), Permission.SET_SPAWN) && !invitedWithoutVisit(player, event.getNewSpawn())) {
            event.setCanceled(true);
            player.displayClientMessage(Msg.denied(Permission.SET_SPAWN), true);
        }
    }

    private static final class ServerPlayerReturn {
        static void recordRespawnVisit(MinecraftServer server, Archive archive, ServerPlayer player, long now) {
            var spawn = server.overworld().getSharedSpawnPos();
            AccessControl.enter(archive, player.getUUID(), new ReturnLocation(server.overworld().dimension().location().toString(),
                spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f), now, ArchiveService.currentPolicy());
            ArchiveService.dirtyData(server);
        }
    }
}
