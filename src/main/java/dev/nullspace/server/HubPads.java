package dev.nullspace.server;

import dev.nullspace.content.Content;
import dev.nullspace.content.DestinationPadBlock;
import dev.nullspace.content.DestinationPadBlockEntity;
import dev.nullspace.core.ActionLimiter;
import dev.nullspace.core.Archive;
import dev.nullspace.core.Basement;
import dev.nullspace.core.Destination;
import dev.nullspace.core.HubPolicy;
import dev.nullspace.core.Translatable;
import dev.nullspace.integration.tempad.TempadGatewayBridge;
import dev.nullspace.text.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class HubPads {
    private HubPads() {}

    private static final Logger LOG = LoggerFactory.getLogger("nullspace");
    private static final ActionLimiter PAD_LIMITER = new ActionLimiter(3, 20);
    private static final Map<UUID, Long> NEXT_USE = new HashMap<>();

    public static void use(ServerPlayer player, BlockPos pos) {
        if (!ArchiveService.DIMENSION.equals(player.level().dimension())) return;
        if (!PAD_LIMITER.tryAcquire(player.getUUID(), player.getServer().getTickCount())) return;
        Archive archive = ArchiveService.registryOf(player.getServer()).at(pos.getX(), pos.getZ()).orElse(null);
        int slot = archive == null ? -1 : Basement.slotAt(archive.originX(), archive.originZ(), pos.getX(), pos.getY(), pos.getZ());
        if (archive == null || !archive.basement().installed() || slot < 0) return;   // a pad that is not on a real slot does nothing
        HubPolicy hub = ArchiveService.hubPolicy();
        if (!hub.enabled()) { Msg.refuse(player, Msg.disabled("pads")); return; }
        if (!Basement.mayUse(archive, player.getUUID(), player.hasPermissions(2), ArchiveService.currentPolicy(), hub)) { Msg.refuse(player, Msg.no("message.nullspace.pad.owner_only")); return; }
        long now = player.getServer().getTickCount();
        Long next = NEXT_USE.get(player.getUUID());
        if (next != null && now < next) { Msg.refuse(player, Msg.no("message.nullspace.pad.cooldown", (int) Math.ceil((next - now) / 20.0))); return; }
        Destination d = archive.basement().destinationOfSlot(slot).orElse(null);
        if (d == null) { Msg.refuse(player, Msg.no("message.nullspace.pad.unset")); return; }

        Component refusal = trip(player, archive, slot, d, hub);
        if (refusal != null) { Msg.refuse(player, refusal); return; }
        NEXT_USE.put(player.getUUID(), now + hub.padCooldownTicks());
        if (NEXT_USE.size() > 256) NEXT_USE.values().removeIf(t -> t < now);
    }

    private static Component trip(ServerPlayer player, Archive archive, int slot, Destination d, HubPolicy hub) {
        var server = player.getServer();
        ResourceKey<Level> dim;
        try { dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(d.dimension())); }
        catch (ResourceLocationException e) { return Msg.no("message.nullspace.pad.missing_dimension", d.name()); }
        ServerLevel level = ArchiveService.DIMENSION.equals(dim) ? null : server.getLevel(dim);
        if (level == null) return Msg.no("message.nullspace.pad.missing_dimension", d.name());
        Vec3 wanted = new Vec3(d.x(), d.y(), d.z());
        Vec3 safe = ReturnSafety.safeSpot(level, wanted);
        if (safe == null) {
            LOG.info("Pad trip of {} to '{}' refused: no safe spot near {} in {}", player.getGameProfile().getName(), d.name(), wanted, d.dimension());
            return Msg.no("message.nullspace.pad.unsafe", d.name());
        }
        var bridge = ArchiveService.bridge();
        Component restriction = bridge.hubRestriction(player, dim, safe, d.yaw());
        if (restriction != null) return restriction;
        int cost = ServerConfig.HUB_CHRONON_COST.get();
        if (cost > 0 && !player.getAbilities().instabuild && TempadGatewayBridge.chrononsCarried(player) < cost)
            return Msg.no("message.nullspace.pad.no_chronons", cost, TempadGatewayBridge.chrononsCarried(player));
        BlockPos pad = slotPos(archive, slot);
        if (hub.useDoor()) {
            var facing = Basement.slotFacing(slot);
            // standing on the plinth, in line with the pad, for a player walking out from the middle of the hall onto it.
            Vec3 at = new Vec3(pad.getX() + 0.5, pad.getY() + DestinationPadBlock.HEIGHT, pad.getZ() + 0.5);
            float walkerYaw = (float) (Math.atan2(facing.dx, -facing.dz) * 180.0 / Math.PI);
            Component error = bridge.openHub(player, at, walkerYaw, Component.literal(d.name()), dim, safe, d.yaw()); // i18n-ok: player-chosen name
            if (error != null) return error;
            if (!charge(player, cost)) { ArchiveService.bridge().closeExits(server, player.getUUID(), false); return Msg.no("message.nullspace.pad.no_chronons", cost, 0); }
            ArchiveService.play(player, Content.ROOM_CHANGE);
            Msg.refuse(player, Msg.ok("message.nullspace.pad.door", d.name()));
            LOG.debug("Pad: {} opened a door to '{}' ({} {})", player.getGameProfile().getName(), d.name(), d.dimension(), safe);
        } else {
            if (!charge(player, cost)) return Msg.no("message.nullspace.pad.no_chronons", cost, 0);
            player.ejectPassengers();
            player.teleportTo(level, safe.x, safe.y, safe.z, d.yaw(), player.getXRot());
            player.resetFallDistance();
            Msg.refuse(player, Msg.ok("message.nullspace.pad.arrived", d.name()));
            LOG.debug("Pad: {} was taken to '{}' ({} {})", player.getGameProfile().getName(), d.name(), d.dimension(), safe);
        }
        return null;
    }

    private static boolean charge(ServerPlayer player, int cost) { return TempadGatewayBridge.chargeChronons(player, cost); }

    public static BlockPos slotPos(Archive archive, int slot) {
        var p = Basement.slot(archive.allocation(), slot);
        return new BlockPos(p.x(), p.y(), p.z());
    }

    static void forget(UUID player) { NEXT_USE.remove(player); }


    public static void syncPad(Level level, BlockPos pos) {
        if (level.isClientSide() || !level.dimension().equals(ArchiveService.DIMENSION) || level.getServer() == null) return;
        syncPad(level, pos, ArchiveService.registryOf(level.getServer()).at(pos.getX(), pos.getZ()).orElse(null));
    }

    public static void syncPad(Level level, BlockPos pos, Archive archive) {
        if (!(level.getBlockEntity(pos) instanceof DestinationPadBlockEntity pad)) return;
        int slot = archive == null ? -1 : Basement.slotAt(archive.originX(), archive.originZ(), pos.getX(), pos.getY(), pos.getZ());
        Destination d = slot < 0 ? null : archive.basement().destinationOfSlot(slot).orElse(null);
        pad.show(d == null ? "" : d.name(), d == null ? "" : d.dimension());
        // the block's own look and light follow the binding: a bound pad glows, an unbound one is dim
        BlockState state = level.getBlockState(pos);
        boolean bound = d != null;
        if (state.is(Content.DESTINATION_PAD.get()) && state.getValue(DestinationPadBlock.BOUND) != bound)
            level.setBlock(pos, state.setValue(DestinationPadBlock.BOUND, bound), 3);
    }

    static void refreshAll(MinecraftServer server, Archive archive) {
        ServerLevel level = server.getLevel(ArchiveService.DIMENSION);
        if (level != null) refreshAll(level, archive);
    }

    public static void refreshAll(ServerLevel level, Archive archive) {
        for (int slot = 0; slot < Basement.SLOT_COUNT; slot++) {
            BlockPos pos = slotPos(archive, slot);
            if (level.isLoaded(pos) && level.getBlockState(pos).is(Content.DESTINATION_PAD.get())) syncPad(level, pos, archive);
        }
    }


    public static void refusePlacement(ServerPlayer player, BlockPos pos) {
        if (!ArchiveService.DIMENSION.equals(player.level().dimension())) { Msg.refuse(player, Msg.no("message.nullspace.pad.place_only_hall")); return; }
        Msg.refuse(player, Msg.no(Basement.slotAtNearest(pos.getX(), pos.getY(), pos.getZ()) < 0 ? "message.nullspace.pad.place_only_slots" : "message.nullspace.pad.place_blocked"));
    }

    // the placement rule for every block a player places in the archive dimension: a refusal key or null. pads only
    // on the twelve slots of an installed hall and only for the owner or an operator; nothing but a pad on a slot;
    // nothing in the lift column
    public static String placementRefusal(ServerPlayer player, Archive archive, BlockPos pos, boolean pad) {
        if (archive == null) return pad ? "message.nullspace.pad.place_only_hall" : null;
        var verdict = Basement.placement(archive.allocation(), pos.getX(), pos.getY(), pos.getZ(), pad);
        if (pad) {
            if (verdict != Basement.Placement.OK) return "message.nullspace.pad.place_only_slots";
            if (!archive.basement().installed()) return "message.nullspace.pad.place_only_hall";
            if (!ArchiveService.hubPolicy().enabled()) return Translatable.disabled("pads");
            return Basement.mayUse(archive, player.getUUID(), player.hasPermissions(2), ArchiveService.currentPolicy(), ArchiveService.hubPolicy()) ? null : "message.nullspace.pad.owner_only";
        }
        if (!archive.basement().installed()) return null;
        return switch (verdict) {
            case LIFT_COLUMN -> "message.nullspace.hall.keep_lift_clear";
            case SLOT_ONLY_PADS -> "message.nullspace.hall.slot_pads_only";
            default -> null;
        };
    }
}
