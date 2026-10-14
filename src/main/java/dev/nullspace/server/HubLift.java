package dev.nullspace.server;

import dev.nullspace.content.Content;
import dev.nullspace.content.LiftPadBlock;
import dev.nullspace.core.ActionLimiter;
import dev.nullspace.core.Archive;
import dev.nullspace.core.Basement;
import dev.nullspace.core.HoldCharge;
import dev.nullspace.core.HubPolicy;
import dev.nullspace.core.Room;
import dev.nullspace.core.Translatable;
import dev.nullspace.text.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// the hall lift. no right click: hold sneak for about a second (hub.liftHoldTicks) on a lift pad
public final class HubLift {
    private HubLift() {}

    private static final Logger LOG = LoggerFactory.getLogger("nullspace");
    private static final HoldCharge HOLD = new HoldCharge();
    // one ride per two seconds per player, so a held key or a bouncing jump cannot ping pong between floors
    private static final ActionLimiter RIDE_LIMITER = new ActionLimiter(1, 40);
    // players already told owner only for the current hold, cleared on release
    private static final Set<UUID> TOLD = new HashSet<>();
    private static long lastPrune;

    static void install(IEventBus bus) {
        bus.addListener(ServerTickEvent.Post.class, HubLift::onTick);
        bus.addListener(LivingEvent.LivingJumpEvent.class, HubLift::onJump);
    }

    private static BlockState padUnder(ServerPlayer player) {
        BlockState state = player.level().getBlockState(player.getOnPos());
        return state.is(Content.LIFT_PAD.get()) ? state : null;
    }

    private static void onTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        ServerLevel level = server.getLevel(ArchiveService.DIMENSION);
        if (level == null) return;
        HubPolicy hub = ArchiveService.hubPolicy();
        var players = level.players();
        for (ServerPlayer player : players) tick(player, hub);
        long now = server.getTickCount();
        if (now - lastPrune >= 200) {
            lastPrune = now;
            Set<UUID> here = new HashSet<>();
            for (ServerPlayer p : players) here.add(p.getUUID());
            HOLD.retainOnly(here);
            TOLD.retainAll(here);
        }
    }

    private static void tick(ServerPlayer player, HubPolicy hub) {
        BlockState pad = player.onGround() && !player.isSpectator() ? padUnder(player) : null;
        boolean charging = pad != null && pad.getValue(LiftPadBlock.WAY) == LiftPadBlock.Way.DOWN && player.isShiftKeyDown();
        HoldCharge.Result result = HOLD.step(player.getUUID(), charging, hub.liftHoldTicks());
        if (!charging) { TOLD.remove(player.getUUID()); return; }
        boolean allowed = allowed(player, hub) == null;
        switch (result) {
            case CHARGING -> { if (allowed) effects(player, HOLD.progress(player.getUUID(), hub.liftHoldTicks())); }
            case FIRED -> ride(player, true);
            case IDLE -> { }
        }
    }

    private static void onJump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !ArchiveService.DIMENSION.equals(player.level().dimension())) return;
        BlockState pad = padUnder(player);
        if (pad == null || pad.getValue(LiftPadBlock.WAY) != LiftPadBlock.Way.UP) return;
        ride(player, false);
    }

    private static String allowed(ServerPlayer player, HubPolicy hub) {
        Archive archive = ArchiveService.registryOf(player.getServer()).at((int) Math.floor(player.getX()), (int) Math.floor(player.getZ())).orElse(null);
        if (archive == null || !archive.basement().installed()) return "message.nullspace.lift.none";
        if (!hub.enabled()) return Translatable.disabled("lift");
        if (!Basement.mayUse(archive, player.getUUID(), player.hasPermissions(2), ArchiveService.currentPolicy(), hub)) return "message.nullspace.lift.owner_only";
        if (holdsLead(player)) return "message.nullspace.lift.leashed";
        return null;
    }

    // the lift moves only the rider, and a stretched lead would break and drop when the rider walks off on the other
    // floor
    private static boolean holdsLead(ServerPlayer player) {
        return !player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(24), m -> m.getLeashHolder() == player).isEmpty();
    }

    private static void effects(ServerPlayer player, float progress) {
        int cells = 10, filled = Math.round(progress * cells);
        String bar = "▮".repeat(filled) + "▯".repeat(cells - filled); // i18n-ok: progress glyphs
        player.displayClientMessage(Msg.ok("message.nullspace.lift.charging", Component.literal(bar)), true);
        ServerLevel level = player.serverLevel();
        double r = 0.55 - 0.35 * progress, a = progress * Math.PI * 6;
        level.sendParticles(ParticleTypes.END_ROD, player.getX() + Math.cos(a) * r, player.getY() + 0.1 + progress * 1.7, player.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 0.2, player.getZ(), 2, 0.25, 0.1, 0.25, 0.02);
        if (Math.round(progress * 20) % 4 == 1) level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.5f, 0.6f + progress * 0.9f);
    }

    public static Vec3 ride(ServerPlayer player, boolean down) {
        HubPolicy hub = ArchiveService.hubPolicy();
        String why = allowed(player, hub);
        if (why != null) {
            if (TOLD.add(player.getUUID()) || !down) Msg.refuse(player, Msg.no(why));
            return null;
        }
        if (player.isPassenger()) { Msg.refuse(player, Msg.no("message.nullspace.lift.dismount")); return null; }
        if (!RIDE_LIMITER.tryAcquire(player.getUUID(), player.getServer().getTickCount())) return null;
        Archive archive = ArchiveService.registryOf(player.getServer()).at((int) Math.floor(player.getX()), (int) Math.floor(player.getZ())).orElseThrow();
        ServerLevel level = player.serverLevel();
        var landing = down ? Basement.lowerLanding(archive.allocation()) : Basement.upperLanding(archive.allocation());
        Vec3 wanted = new Vec3(landing.x() + 0.5, landing.y(), landing.z() + 0.5);
        Vec3 safe = ReturnSafety.safeSpot(level, wanted);
        // a spot found by the search must still be in the right place: in the hall (down) or above the starter floor
        // (up), not a floor away
        boolean sane = safe != null && (down ? Basement.interior(archive.allocation()).contains((int) Math.floor(safe.x), (int) Math.floor(safe.y), (int) Math.floor(safe.z))
            : safe.y >= Room.FLOOR_Y + 1 && archive.allocation().contains((int) Math.floor(safe.x), (int) Math.floor(safe.z)));
        if (!sane) {
            LOG.info("Lift refused for {}: landing {} {} is blocked", player.getGameProfile().getName(), down ? "in the hall" : "upstairs", wanted);
            Msg.refuse(player, Msg.no("message.nullspace.lift.blocked"));
            return null;
        }
        player.ejectPassengers();
        puff(level, player.position());
        player.teleportTo(level, safe.x, safe.y, safe.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        player.resetFallDistance();
        HOLD.clear(player.getUUID());
        puff(level, safe);
        level.playSound(null, BlockPos.containing(safe), Content.ROOM_CHANGE.get(), SoundSource.BLOCKS, 0.7f, down ? 0.8f : 1.2f);
        Msg.refuse(player, Msg.ok(down ? "message.nullspace.lift.down" : "message.nullspace.lift.up"));
        LOG.debug("Lift: {} rode {} to {}", player.getGameProfile().getName(), down ? "down" : "up", safe);
        return safe;
    }

    private static void puff(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 0.9, at.z, 24, 0.3, 0.7, 0.3, 0.05);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 0.2, at.z, 8, 0.25, 0.1, 0.25, 0.04);
    }
}
