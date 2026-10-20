package dev.nullspace.server;

import dev.nullspace.Nullspace;
import dev.nullspace.content.Content;
import dev.nullspace.content.ShellBlock;
import dev.nullspace.core.AccessControl;
import dev.nullspace.core.ActionLimiter;
import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.ArchiveSettings;
import dev.nullspace.core.Basement;
import dev.nullspace.core.CardGrant;
import dev.nullspace.core.CardPolicy;
import dev.nullspace.core.HubPolicy;
import dev.nullspace.core.ArchiveRegistry;
import dev.nullspace.core.Permission;
import dev.nullspace.core.ReturnLocation;
import dev.nullspace.core.Room;
import dev.nullspace.core.ServerPolicy;
import dev.nullspace.core.Surface;
import dev.nullspace.core.SurfaceGroup;
import dev.nullspace.core.TeamPolicy;
import dev.nullspace.core.TeamProvider;
import dev.nullspace.core.TeamRelation;
import dev.nullspace.core.TeamSweep;
import dev.nullspace.core.TicketPlanner;
import dev.nullspace.core.Translatable;
import dev.nullspace.core.Visit;
import dev.nullspace.integration.carryon.CarryOnGuard;
import dev.nullspace.integration.tempad.ArchiveCircleAccess;
import dev.nullspace.integration.tempad.CircleNotice;
import dev.nullspace.integration.tempad.TempadGatewayBridge;
import dev.nullspace.integration.waystones.WaystonesGuard;
import dev.nullspace.text.Msg;
import com.mojang.authlib.GameProfile;
import dev.nullspace.core.Invitation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// the adapter between the domain model and minecraft: persistence (ArchiveSavedData), the archive dimension, starter
// room placement and the tempad GatewayAccess
public final class ArchiveService implements TempadGatewayBridge.GatewayAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger("nullspace");
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Nullspace.id("archive"));
    private static final TicketType<ChunkPos> ANCHOR_TICKET =
        TicketType.create("nullspace_anchor", Comparator.comparingLong(ChunkPos::toLong));

    private static ArchiveService instance;

    // set once at startup by the team integration, if any. null means no team mod, so the feature is absent
    private static volatile TeamProvider teamProvider;
    private static final TeamSweep TEAM_SWEEP = new TeamSweep();
    // owners already told their team access is suspended, so it is said once and not every second
    private final Set<UUID> teamNotified = new HashSet<>();

    private TempadGatewayBridge bridge;
    private Set<TicketPlanner.Ticket> currentTickets = Set.of();
    // a new level object (world reopened in the same jvm) makes the old ticket set meaningless
    private ServerLevel ticketLevel;

    static volatile long clockSkew;

    public static long now() { return System.currentTimeMillis() + clockSkew; }

    static volatile Map<UUID, String> refusalLog;

    private ArchiveService() {}

    public static void install(IEventBus modBus, IEventBus forgeBus) {
        instance = new ArchiveService();
        instance.bridge = TempadGatewayBridge.install(instance);
        // tempad's access registry is an unsynchronised map: register on the main thread in common setup, not during parallel mod construction.
        modBus.addListener(FMLCommonSetupEvent.class, e -> e.enqueueWork(() ->
            ArchiveCircleAccess.register(new ArchiveCircleAccess(
                ServerConfig.TEMPAD_CIRCLE::get, ArchiveService::now, ArchiveService::currentPolicy, ArchiveService::registryOf))));
        CircleNotice.install(forgeBus);
        forgeBus.addListener(ServerTickEvent.Post.class, instance::onServerTick);
        forgeBus.addListener(ServerStoppingEvent.class, instance::onServerStopping);
        forgeBus.addListener(ServerStartedEvent.class, e -> EditJournal.recover(e.getServer(), e.getServer().getLevel(DIMENSION)));
        forgeBus.addListener(ServerStoppedEvent.class, e -> { instance.currentTickets = Set.of(); instance.ticketLevel = null; });
        forgeBus.addListener(AddReloadListenerEvent.class, e -> e.addListener(PresetManager.INSTANCE));
        ArchiveProtection.install(forgeBus);
        DeathAndSpawn.install(forgeBus);
        RecallModule.install(forgeBus);
        HubLift.install(forgeBus);
        TravelGuard.install(forgeBus);
        WindowHeal.install(forgeBus);
        RoomBanner.install(forgeBus);
        ExitUpgrade.install(forgeBus);
        // soft dependency: the integration class is only touched (and loaded) when carry on is present. an
        // unsupported version (event class or data api changed) logs a warning and leaves the integration off, launch
        // is not blocked
        if (ModList.get().isLoaded("carryon")) {
            try { CarryOnGuard.install(forgeBus); }
            catch (LinkageError | RuntimeException t) { LOGGER.warn("Carry On is installed but its API is not what Nullspace's Carry On protection was written against; the protection is off. Visitors may carry and put down things in archives without the archive's rights.", t); }
        }
        // same for waystones. without it TravelGuard still refuses the trip, but only after waystones has charged for it
        if (ModList.get().isLoaded("waystones")) {
            try { WaystonesGuard.install(forgeBus); }
            catch (LinkageError | RuntimeException t) { LOGGER.warn("Waystones is installed but its API is not what Nullspace's Waystones guard was written against; the guard is off. Refused waystone trips into archives will still cost their price.", t); }
        }
        forgeBus.addListener(PlayerEvent.PlayerLoggedInEvent.class, instance::onLogin);
        forgeBus.addListener(RegisterCommandsEvent.class, e -> ArchiveCommands.register(e.getDispatcher()));
    }

    public static void registerTeamProvider(TeamProvider provider) { teamProvider = provider; }
    // a team mod changed something: re-check grants on the next server tick, not from inside its event
    public static void requestTeamRecheck() { TEAM_SWEEP.request(); }

    public static ArchiveRegistry registryOf(MinecraftServer server) { return instance().registry(server); }
    static void dirty(MinecraftServer server) { instance().markDirty(server); }
    public static ServerPolicy currentPolicy() { return policy(); }
    public static HubPolicy hubPolicy() {
        return new HubPolicy(ServerConfig.HUB.get(), ServerConfig.HUB_MAX_DESTINATIONS.get(), ServerConfig.HUB_LIFT_HOLD_TICKS.get(),
            ServerConfig.HUB_PAD_COOLDOWN_TICKS.get(), ServerConfig.HUB_USE_DOOR.get());
    }

    static void evictToFallback(ServerPlayer player) { instance().evict(player, null); clearArchiveSpawn(player); }
    static void dirtyData(MinecraftServer server) { instance().markDirty(server); }

    static Component setSetting(ServerPlayer owner, String key, boolean value) {
        Archive archive = ownArchive(owner);
        var old = archive.settings();
        int index = switch (key) { case "keepinventory" -> 0; case "keepxp" -> 1; case "spawn" -> 2;
            default -> throw new ArchiveException("error.nullspace.setting.unknown"); };
        var updated = switch (index) {
            case 0 -> new ArchiveSettings(value, old.keepXp(), old.allowSpawn());
            case 1 -> new ArchiveSettings(old.keepInventory(), value, old.allowSpawn());
            default -> new ArchiveSettings(old.keepInventory(), old.keepXp(), value);
        };
        archive.updateSettings(owner.getUUID(), updated);
        instance().markDirty(owner.getServer());
        var effective = policy().effective(updated);
        boolean grave = DeathAndSpawn.graveModLoaded();
        boolean inEffect = switch (index) { case 0 -> effective.keepInventory() && !grave; case 1 -> effective.keepXp() && !grave; default -> effective.allowSpawn(); };
        var message = Msg.ok("message.nullspace.setting.saved", Component.translatable("gui.nullspace.setting." + key), Component.translatable(value ? "gui.nullspace.on" : "gui.nullspace.off"));
        if (value && !inEffect) message.append(Component.literal("\n")).append(Msg.hint("message.nullspace.setting.blocked")); // i18n-ok: line break
        return message;
    }

    private static TicketPlanner.Grant grant(ServerPlayer player, Archive archive) {
        return TicketPlanner.grantFor(instance().registry(player.getServer()).archives().values(), archive, policy());
    }

    // installs on the archive whose terminal was clicked, not the clicker's own
    public static void installAnchor(ServerPlayer player, BlockPos terminal, ItemStack stack) {
        Archive archive = instance().registry(player.getServer()).at(terminal.getX(), terminal.getZ()).orElse(null);
        if (archive == null || !AccessControl.isOwner(archive, player.getUUID())) {
            Msg.refuse(player, Msg.no("message.nullspace.anchor.owner_only"));
        } else if (archive.anchorInstalled()) {
            Msg.refuse(player, Msg.no("message.nullspace.anchor.already"));
        } else if (!policy().allowAnchors()) {
            Msg.refuse(player, Msg.disabled("anchor"));
        } else if (grant(player, archive) != TicketPlanner.Grant.GRANTED) {
            Msg.refuse(player, Msg.no(grant(player, archive) == TicketPlanner.Grant.TOO_LARGE ? "message.nullspace.anchor.too_large" : "message.nullspace.anchor.no_slot"));
        } else {
            archive.installAnchor(player.getUUID());
            if (!player.getAbilities().instabuild) stack.shrink(1);
            instance().markDirty(player.getServer());
            play(player, Content.ANCHOR_INSTALL);
            Msg.say(player, Msg.ok("message.nullspace.anchor.installed"));
            Msg.say(player, Msg.hint("message.nullspace.anchor.installed.hint"));
        }
    }

    public static void installWaypoint(ServerPlayer player, BlockPos terminal, ItemStack stack) { WaypointHall.installFromTerminal(player, terminal, stack); }

    public static void installRecall(ServerPlayer player, BlockPos terminal, ItemStack stack) { RecallModule.installFromTerminal(player, terminal, stack); }

    static int adminEvict(MinecraftServer server, UUID visitor) {
        ArchiveService s = instance();
        for (Archive archive : s.registry(server).archives().values()) {
            if (!archive.activeVisits().containsKey(visitor)) continue;
            Optional<ReturnLocation> location = AccessControl.exit(archive, visitor);
            ServerPlayer online = server.getPlayerList().getPlayer(visitor);
            if (online != null) { s.evict(online, location.orElse(null)); clearArchiveSpawn(online); }
            else location.ifPresent(l -> ArchiveSavedData.get(server.overworld()).pendingReturns().put(visitor, l));
            s.markDirty(server);
            return 1;
        }
        // not a recorded visitor but standing in the archive dimension (an owner, typically): the operator can still pull them out.
        ServerPlayer inside = server.getPlayerList().getPlayer(visitor);
        if (inside != null && DIMENSION.equals(inside.level().dimension()) && !inside.isDeadOrDying()) { s.evict(inside, null); return 1; }
        return 0;
    }

    static void play(ServerPlayer player, DeferredHolder<SoundEvent, SoundEvent> sound) {
        player.level().playSound(null, player.blockPosition(), sound.get(), SoundSource.BLOCKS, 0.7f, 1f);
    }

    static TempadGatewayBridge bridge() { return instance().bridge; }

    public static TempadGatewayBridge.GatewayAccess gateway() { return instance(); }

    static Archive ownArchive(ServerPlayer player) {
        Archive archive = instance().registry(player.getServer()).find(player.getUUID()).orElse(null);
        if (archive == null || !archive.initialized()) throw new ArchiveException("error.nullspace.no_archive");
        return archive;
    }

    // an accepted invitation messages the guest, and an owner could invite and remove in a loop, so the rate is
    // capped
    private static final ActionLimiter INVITE_LIMITER = new ActionLimiter(5, 100);

    public static Component invite(ServerPlayer owner, GameProfile guest, Invitation.Kind kind, long hours, Set<Permission> permissions) {
        ArchiveService s = instance();
        if (throttled(INVITE_LIMITER, owner)) throw new ArchiveException("error.nullspace.slow_down");
        AccessControl.invite(ownArchive(owner), owner.getUUID(), guest.getId(), kind, hours * 3_600_000L, permissions, now(), policy());
        s.markDirty(owner.getServer());
        String suffix = switch (kind) { case PERMANENT -> "permanent"; case TIMED -> "timed"; case SINGLE_VISIT -> "single"; };
        ServerPlayer online = owner.getServer().getPlayerList().getPlayer(guest.getId());
        if (online != null) {
            Msg.say(online, Msg.ok("message.nullspace.invite.received." + suffix, owner.getGameProfile().getName(), hours));
            Msg.say(online, Msg.hint("message.nullspace.invite.received.hint"));
        }
        return Msg.ok("message.nullspace.invite.sent." + suffix, guest.getName(), hours);
    }

    static Component setPermissions(ServerPlayer owner, GameProfile guest, Set<Permission> permissions) {
        AccessControl.setPermissions(ownArchive(owner), owner.getUUID(), guest.getId(), permissions, policy());
        instance().markDirty(owner.getServer());
        return Msg.ok("message.nullspace.permissions.updated", guest.getName());
    }

    static Component setTeamAccess(ServerPlayer owner, boolean on) {
        ArchiveService s = instance();
        Archive archive = ownArchive(owner);
        if (on) AccessControl.enableTeamAccess(archive, owner.getUUID(), policy());
        else AccessControl.disableTeamAccess(archive, owner.getUUID());
        s.teamNotified.remove(owner.getUUID());
        s.markDirty(owner.getServer());
        s.sweepTeams(owner.getServer());
        var view = AccessControl.teamView(archive, policy());
        return on ? Msg.ok("message.nullspace.team.on", view.teamName() == null ? "" : view.teamName(), view.size()) : Msg.ok("message.nullspace.team.off");
    }

    static Component setTeamPermissions(ServerPlayer owner, Set<Permission> permissions) {
        AccessControl.setTeamPermissions(ownArchive(owner), owner.getUUID(), permissions, policy());
        instance().markDirty(owner.getServer());
        return Msg.ok("message.nullspace.team.permissions.updated");
    }

    static List<Component> teamStatusLines(ServerPlayer owner) {
        Archive archive = ownArchive(owner);
        ServerPolicy policy = policy();
        var view = AccessControl.teamView(archive, policy);
        var lines = new ArrayList<Component>();
        String team = view.teamName() == null ? "" : view.teamName();
        lines.add(switch (view.status()) {
            case UNAVAILABLE -> Msg.ok("command.nullspace.team.unavailable");
            case OFF -> Msg.ok("command.nullspace.team.off", team, view.size());
            case ACTIVE -> Msg.ok("command.nullspace.team.on", team, view.size());
            case NO_TEAM -> Msg.no("message.nullspace.team.paused.no_team", policy.teams().maxTeamSize());
            case SUSPENDED -> Msg.no("message.nullspace.team.paused.suspended", policy.teams().maxTeamSize());
            case TOO_LARGE -> Msg.no("message.nullspace.team.paused.too_large", policy.teams().maxTeamSize());
        });
        if (view.status() != AccessControl.TeamStatus.UNAVAILABLE) {
            var names = new ArrayList<Component>();
            for (Permission p : Permission.values()) if (archive.teamAccess().permissions().contains(p) && policy.allowedGuestPermissions().contains(p)) names.add(Component.translatable(Msg.permissionKey(p)));
            lines.add(Msg.ok("command.nullspace.team.permissions", ComponentUtils.formatList(names, Component.literal(", ")))); // i18n-ok: list separator
        }
        return lines;
    }

    void sweepTeams(MinecraftServer server) {
        var provider = teamProvider;
        if (provider != null && !provider.ready()) return; // unanswered is not no: do not evict while the team data is still loading
        ServerPolicy policy = policy();
        boolean changed = false;
        ArchiveSavedData data = null;
        for (Archive archive : registry(server).archives().values()) {
            if (archive.teamAccess().enabled()) notifyTeamStatus(server, archive, policy);
            if (archive.activeVisits().isEmpty()) continue;
            for (UUID id : AccessControl.teamVisitorsToEvict(archive, policy)) {
                Optional<ReturnLocation> ended = AccessControl.exit(archive, id);
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                if (data == null) data = ArchiveSavedData.get(server.overworld());
                switch (AccessControl.departure(ended, online != null, online != null && DIMENSION.equals(online.level().dimension()))) {
                    case EVICT_NOW -> evict(online, ended.orElse(null));
                    case RETURN_AT_LOGIN -> data.pendingReturns().put(id, ended.get());
                    case NONE -> { }
                }
                if (online != null && removalClearsSpawn(archive, online)) clearArchiveSpawn(online);
                LOGGER.info("Team access ended for {} in the archive of {}", id, archive.owner());
                changed = true;
            }
        }
        if (changed) markDirty(server);
    }

    private void notifyTeamStatus(MinecraftServer server, Archive archive, ServerPolicy policy) {
        var status = AccessControl.teamView(archive, policy).status();
        String reason = switch (status) { case SUSPENDED -> "suspended"; case NO_TEAM -> "no_team"; case TOO_LARGE -> "too_large"; default -> null; };
        if (reason == null) { teamNotified.remove(archive.owner()); return; }
        ServerPlayer owner = server.getPlayerList().getPlayer(archive.owner());
        if (owner == null || !teamNotified.add(archive.owner())) return;
        Msg.say(owner, Msg.no("message.nullspace.team.paused." + reason, policy.teams().maxTeamSize()));
        Msg.say(owner, Msg.hint("message.nullspace.team.paused.hint"));
    }

    static Component savePreset(ServerPlayer owner, String name, Set<Permission> permissions) {
        ownArchive(owner).savePreset(owner.getUUID(), name, permissions);
        instance().markDirty(owner.getServer());
        return Msg.ok("message.nullspace.preset.saved", name);
    }

    static Component remove(ServerPlayer owner, GameProfile guest) {
        ArchiveService s = instance();
        MinecraftServer server = owner.getServer();
        Archive archive = ownArchive(owner);
        Optional<ReturnLocation> returnLocation = AccessControl.remove(archive, owner.getUUID(), guest.getId());
        ServerPlayer online = server.getPlayerList().getPlayer(guest.getId());
        ArchiveSavedData data = ArchiveSavedData.get(server.overworld());
        switch (AccessControl.departure(returnLocation, online != null, online != null && DIMENSION.equals(online.level().dimension()))) {
            case EVICT_NOW -> s.evict(online, returnLocation.orElse(null));
            case RETURN_AT_LOGIN -> data.pendingReturns().put(guest.getId(), returnLocation.get());
            case NONE -> { }
        }
        if (online != null && removalClearsSpawn(archive, online)) clearArchiveSpawn(online);
        s.markDirty(server);
        return Msg.ok("message.nullspace.guest.removed", guest.getName());
    }

    public static boolean removalClearsSpawn(Archive archive, ServerPlayer player) {
        BlockPos spawn = player.getRespawnPosition();
        return spawn != null && DIMENSION.equals(player.getRespawnDimension()) && archive.allocation().contains(spawn.getX(), spawn.getZ());
    }

    static void clearArchiveSpawn(ServerPlayer player) {
        if (DIMENSION.equals(player.getRespawnDimension())) player.setRespawnPosition(Level.OVERWORLD, null, 0f, false, false);
    }

    private void evict(ServerPlayer player, ReturnLocation location) { evict(player, location, "message.nullspace.evicted"); }

    private void evict(ServerPlayer player, ReturnLocation location, String messageKey) {
        if (player.isDeadOrDying()) return; // do not teleport a player on the death screen: their respawn re-checks access (DeathAndSpawn.onRespawn)
        var target = returnTarget(player.getServer(), location, player.getGameProfile().getName());
        ServerLevel level = player.getServer().getLevel(target.dimension());
        player.teleportTo(level, target.position().x, target.position().y, target.position().z, target.yaw(), 0f);
        Msg.say(player, Msg.no(messageKey));
    }

    // where a leaving or removed visitor goes. the recorded entry point can be blocked, lava or void by now, or its
    // dimension gone, so it is checked here: nearest safe spot, else the overworld spawn
    public static TempadGatewayBridge.ReturnTarget returnTarget(MinecraftServer server, ReturnLocation stored, String who) {
        if (stored != null) {
            ResourceKey<Level> dim = null;
            try { dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(stored.dimension())); } catch (ResourceLocationException ignored) { }
            ServerLevel level = dim == null || DIMENSION.equals(dim) ? null : server.getLevel(dim);
            if (level != null) {
                Vec3 wanted = new Vec3(stored.x(), stored.y(), stored.z());
                Vec3 safe = ReturnSafety.safeSpot(level, wanted);
                if (safe != null) {
                    if (!safe.equals(wanted)) LOGGER.info("Return point of {} in {} at {} was unsafe; using {}", who, dim.location(), wanted, safe);
                    return new TempadGatewayBridge.ReturnTarget(dim, safe, stored.yaw());
                }
                LOGGER.warn("No safe return near {} in {} for {}; using the overworld spawn", wanted, dim.location(), who);
            } else {
                LOGGER.warn("Return dimension {} of {} is unavailable; using the overworld spawn", stored.dimension(), who);
            }
        }
        ServerLevel fallback = server.overworld();
        return new TempadGatewayBridge.ReturnTarget(fallback.dimension(), ReturnSafety.spawnSpot(fallback), 0f);
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        ArchiveSavedData data = ArchiveSavedData.get(server.overworld());
        ReturnLocation pending = data.pendingReturns().remove(player.getUUID());
        if (pending == null) TravelGuard.dropStaleVisits(player);
        if (pending != null) { data.setDirty(); evict(player, pending); clearArchiveSpawn(player); return; }
        sweepExpired(server);
        if (DIMENSION.equals(player.level().dimension()) && !player.hasPermissions(2)) {
            Archive archive = data.registry().at((int) Math.floor(player.getX()), (int) Math.floor(player.getZ())).orElse(null);
            boolean belongs = archive != null && (archive.owner().equals(player.getUUID()) || archive.activeVisits().containsKey(player.getUUID()));
            if (!belongs) { evict(player, null); clearArchiveSpawn(player); }
        }
    }

    public static Component foreignTravelRefusal(ServerPlayer player, ResourceKey<Level> to, BlockPos at) { return TravelGuard.refusal(player, player.level().dimension(), to, at); }
    public static boolean entityPickupDenied(ServerPlayer player, Entity target) { return ArchiveProtection.entityPickupDenied(player, target); }
    public static boolean ownerCarryDenied(ServerPlayer carrier, ServerPlayer victim) { return ArchiveProtection.ownerCarryDenied(carrier, victim); }
    public static boolean carriedPlacementDenied(ServerPlayer player, Level level, BlockPos pos) { return ArchiveProtection.carriedPlacementDenied(player, level, pos); }

    public static boolean mayDo(ServerPlayer player, BlockPos pos, Permission... required) {
        if (player.hasPermissions(2)) return true;
        Archive archive = instance().registry(player.getServer()).at(pos.getX(), pos.getZ()).orElse(null);
        if (archive == null) return false;
        ServerPolicy policy = policy();
        long now = now();
        for (Permission permission : required) if (!AccessControl.can(archive, player.getUUID(), permission, now, policy)) return false;
        return true;
    }

    private static ArchiveService instance() {
        if (instance == null) throw new IllegalStateException("ArchiveService.install was not called");
        return instance;
    }

    // vanilla cannot rate limit block clicks, and each of these spawns an entity, a packet or a log line, so extra
    // clicks are ignored
    private static final ActionLimiter EXIT_LIMITER = new ActionLimiter(2, 20);
    private static final ActionLimiter TERMINAL_LIMITER = new ActionLimiter(4, 20);
    private static final ActionLimiter APPLY_LIMITER = new ActionLimiter(10, 20);

    private static boolean throttled(ActionLimiter limiter, ServerPlayer player) {
        return !limiter.tryAcquire(player.getUUID(), player.getServer().getTickCount());
    }

    public static void useCartridge(ServerPlayer player, InteractionHand hand) { instance().doUseCartridge(player, hand); }
    public static void openTerminal(ServerPlayer player, BlockPos pos) { if (!throttled(TERMINAL_LIMITER, player)) instance().doOpenTerminal(player); }
    public static void applySurface(ServerPlayer player, BlockPos pos, ItemStack tool) {
        if (throttled(APPLY_LIMITER, player)) return;
        Component result = applySurfaceChecked(player, pos, tool);
        LOGGER.debug("Applicator by {} at {} ({}): {}", player.getGameProfile().getName(), pos.toShortString(), player.isShiftKeyDown() ? "sneak" : "click", result.getString());
        Msg.refuse(player, result);
    }

    static Component applySurfaceChecked(ServerPlayer player, BlockPos pos, ItemStack tool) {
        MinecraftServer server = player.getServer();
        if (!DIMENSION.equals(player.level().dimension())) return Msg.no("message.nullspace.applicator.outside");
        BlockState state = player.level().getBlockState(pos);
        if (!RoomBuilder.structural(state)) return Msg.no("message.nullspace.applicator.not_shell");
        if (player.isShiftKeyDown()) {
            String current = state.getBlock() == Content.WINDOW.get() ? Finishes.WINDOW
                : "nullspace:" + state.getValue(ShellBlock.FINISH).getSerializedName();
            tool.set(Content.SELECTED_FINISH.get(), current);
            return Msg.ok("message.nullspace.applicator.copied", Finishes.name(current));
        }
        if (!mayDo(player, pos, Permission.BUILD)) return Msg.denied(Permission.BUILD);
        ItemStack offhand = player.getOffhandItem();
        boolean shortcut = !offhand.isEmpty() && offhand != tool;
        String finish = shortcut ? Finishes.fromHeld(offhand).orElse(null) : tool.get(Content.SELECTED_FINISH.get());
        if (shortcut && finish == null) return Msg.no("message.nullspace.applicator.no_match");
        if (finish == null || !Finishes.supported(finish)) return Msg.no("message.nullspace.applicator.choose_first");
        ServerLevel level = server.getLevel(DIMENSION);
        Archive archive = instance().registry(server).at(pos.getX(), pos.getZ()).orElse(null);
        if (level == null || archive == null) return Msg.no("message.nullspace.applicator.not_archive");
        var target = RoomBuilder.shellState(finish);
        boolean sameWindow = state.is(Content.WINDOW.get()) && target.is(Content.WINDOW.get()); // the window's axis is derived, not part of the finish
        if (state == target || sameWindow) return Msg.ok("message.nullspace.applicator.already", Finishes.name(finish));
        var room = RoomBuilder.roomOfShell(archive, pos);
        if (room.isPresent()) {
            var bounds = room.get().shell(archive.allocation());
            var surface = RoomBuilder.surfaceOf(pos.getX(), pos.getY(), pos.getZ(), bounds);
            room.get().setIndividualFinish(RoomBuilder.coordinate(room.get(), archive, surface, pos.getX(), pos.getY(), pos.getZ()), finish);
            instance().markDirty(server);
        }
        level.setBlock(pos, target, Block.UPDATE_ALL);
        play(player, Content.APPLICATOR_SOUND);
        return Msg.ok(room.isPresent() ? "message.nullspace.applicator.applied" : "message.nullspace.applicator.applied_tunnel", Finishes.name(finish));
    }

    static void selectFinish(ServerPlayer player, String finish) {
        ItemStack stack = player.getMainHandItem().is(Content.APPLICATOR.get()) ? player.getMainHandItem()
            : player.getOffhandItem().is(Content.APPLICATOR.get()) ? player.getOffhandItem() : ItemStack.EMPTY;
        if (stack.isEmpty()) { Msg.refuse(player, Msg.no("message.nullspace.applicator.hold")); return; }
        if (!Finishes.supported(finish)) { Msg.refuse(player, Msg.no("message.nullspace.applicator.unknown")); return; }
        stack.set(Content.SELECTED_FINISH.get(), finish);
        LOGGER.debug("Applicator finish selected by {}: {}", player.getGameProfile().getName(), finish);
        Msg.refuse(player, Msg.ok("message.nullspace.applicator.selected", Finishes.name(finish)));
    }

    static void paintSurface(ServerPlayer owner, int roomId, int surfaceOrdinal, String finish) {
        Archive archive = ownArchive(owner);
        if (!Finishes.supported(finish)) throw new ArchiveException("error.nullspace.finish.unsupported");
        var room = archive.room(roomId);
        var surface = Surface.values()[Math.floorMod(surfaceOrdinal, Surface.values().length)];
        room.setSurfaceFinish(surface, finish);
        RoomBuilder.repaintSurface(owner.getServer().getLevel(DIMENSION), archive, room, surface);
        instance().markDirty(owner.getServer());
    }

    static Component renameRoom(ServerPlayer owner, int roomId, String name) {
        Archive archive = ownArchive(owner);
        Room room = archive.renameRoom(owner.getUUID(), roomId, name);
        instance().markDirty(owner.getServer());
        return room.name().isEmpty() ? Msg.ok("message.nullspace.room.unnamed", room.id()) : Msg.ok("message.nullspace.room.renamed", room.id(), room.name());
    }

    static void paintEveryRoom(ServerPlayer owner, int groupOrdinal, String finish) { paintEveryRoom(owner, owner.getServer().getLevel(DIMENSION), groupOrdinal, finish); }
    static void paintEveryRoom(ServerPlayer owner, ServerLevel level, int groupOrdinal, String finish) {
        Archive archive = ownArchive(owner);
        if (!Finishes.supported(finish)) throw new ArchiveException("error.nullspace.finish.unsupported");
        var group = SurfaceGroup.values()[Math.floorMod(groupOrdinal, SurfaceGroup.values().length)];
        archive.paintEveryRoom(owner.getUUID(), group, finish);
        RoomBuilder.repaintEveryRoom(level, archive, group);
        instance().markDirty(owner.getServer());
    }

    static void paintTunnels(ServerPlayer owner, int groupOrdinal, String finish) { paintTunnels(owner, owner.getServer().getLevel(DIMENSION), groupOrdinal, finish); }
    static void paintTunnels(ServerPlayer owner, ServerLevel level, int groupOrdinal, String finish) {
        Archive archive = ownArchive(owner);
        if (!Finishes.supported(finish)) throw new ArchiveException("error.nullspace.finish.unsupported");
        if (Finishes.WINDOW.equals(finish)) throw new ArchiveException("error.nullspace.finish.no_tunnel_window");
        archive.setTunnelFinish(owner.getUUID(), SurfaceGroup.values()[Math.floorMod(groupOrdinal, SurfaceGroup.values().length)], finish);
        RoomBuilder.repaintTunnels(level, archive);
        instance().markDirty(owner.getServer());
    }
    public static void exit(ServerPlayer player) {
        if (throttled(EXIT_LIMITER, player)) return;
        Component result = instance().bridge.openExit(player);
        if (result != null) player.displayClientMessage(result, true); else play(player, Content.EXIT_OPEN);
    }

    private ArchiveRegistry registry(MinecraftServer server) { return ArchiveSavedData.get(server.overworld()).registry(); }
    private void markDirty(MinecraftServer server) { ArchiveSavedData.get(server.overworld()).setDirty(); }

    private static Set<Permission> guestPermissions(ServerPolicy defaults) {
        Set<Permission> set = EnumSet.noneOf(Permission.class);
        for (String name : ServerConfig.GUEST_PERMISSIONS.get()) for (Permission p : Permission.values()) if (p != Permission.ADMIN && p.name().equalsIgnoreCase(name)) set.add(p);
        return set;
    }

    @Override public boolean allowEmergencyExit() { return ServerConfig.EMERGENCY_EXIT.get(); }

    private static ServerPolicy policy() {
        ServerPolicy defaults = ServerPolicy.DEFAULT;
        return new ServerPolicy(ServerConfig.KEEP_INVENTORY.get(), ServerConfig.KEEP_XP.get(), ServerConfig.SPAWN.get(),
            ServerConfig.ANCHORS.get(), Math.max(0, ServerConfig.MAX_ANCHORS.get()), Math.min(4096, Math.max(1, ServerConfig.MAX_ANCHOR_CHUNKS.get())),
            Math.min(256, Math.max(1, ServerConfig.MAX_ROOMS.get())), Math.min(4096, Math.max(0, ServerConfig.MAX_GUESTS.get())),
            Math.max(1L, ServerConfig.MAX_TIMED_HOURS.get() * 3_600_000L), guestPermissions(defaults),
            ServerConfig.RECALL.get(), ServerConfig.RECALL_MINUTES.get(),
            ServerConfig.MAX_ROOM_WIDTH.get(), ServerConfig.MAX_CEILING_HEIGHT.get(), teamPolicy(),
            new CardPolicy(ServerConfig.CARDS_ENABLED.get(), ServerConfig.CARDS_MAX_LIVE.get()));
    }

    private static TeamPolicy teamPolicy() {
        var provider = teamProvider;
        if (provider == null) return TeamPolicy.NONE;
        return new TeamPolicy(TeamPolicy.parseMode(ServerConfig.TEAMS_MODE.get()),
            "officer".equalsIgnoreCase(ServerConfig.TEAMS_MINIMUM_RANK.get()) ? TeamRelation.OFFICER : TeamRelation.MEMBER,
            ServerConfig.TEAMS_MAX_SIZE.get(), provider);
    }

    private void doUseCartridge(ServerPlayer player, InteractionHand hand) {
        MinecraftServer server = player.getServer();
        Archive existing = registry(server).find(player.getUUID()).orElse(null);
        if (existing == null) {
            var offered = PresetManager.INSTANCE.offered();
            if (offered.size() != 1) {
                Msg.say(player, Msg.ok("message.nullspace.claim.choose"));
                for (var preset : offered) {
                    Msg.say(player, Component.literal(" [" + preset.displayName() + "] ").withStyle(style -> style // i18n-ok: preset names are datapack text
                        .withColor(Msg.AMBER)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, ClaimCommandText.of(preset.id().toString())))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(preset.description())))));  // i18n-ok: datapack text
                }
                Msg.say(player, Msg.hint("message.nullspace.claim.choose.hint"));
                return;
            }
            claim(player, offered.get(0).id().toString());
        } else {
            Msg.say(player, Msg.ok("message.nullspace.cartridge.existing"));
        }
        Archive archive = registry(server).find(player.getUUID()).orElse(null);
        if (archive != null) {
            // the cartridge is in `hand`; the projector to link is the item in the other hand (or, failing that, anywhere on the hotbar/offhand).
            ItemStack other = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            boolean linked = !other.isEmpty() && bridge.linkProjector(player, other, archive.owner());
            if (linked) Msg.refuse(player, Msg.ok("message.nullspace.cartridge.linked"));
        }
    }

    // one time claim: replacing a cartridge or repeating the command does not regenerate starter contents
    static void claim(ServerPlayer player, String presetId) {
        ArchiveService s = instance();
        MinecraftServer server = player.getServer();
        ArchiveRegistry registry = s.registry(server);
        if (!player.hasPermissions(2) && !player.getInventory().hasAnyMatching(st -> st.is(Content.CARTRIDGE.get())))
            throw new ArchiveException("error.nullspace.claim.need_cartridge");
        if (registry.find(player.getUUID()).isPresent()) throw new ArchiveException("error.nullspace.claim.already");
        if (PresetManager.INSTANCE.offered().stream().noneMatch(p -> p.id().toString().equals(presetId))) throw new ArchiveException("error.nullspace.claim.unavailable");
        ServerLevel archiveLevel = server.getLevel(DIMENSION);
        if (archiveLevel == null) throw new ArchiveException("error.nullspace.claim.no_dimension");
        Archive archive = registry.claim(player.getUUID(), presetId);
        StarterRooms.place(archiveLevel, archive);
        archive.markInitialized();
        s.markDirty(server);
        Msg.say(player, Msg.ok("message.nullspace.claim.welcome"));
        Msg.say(player, Msg.hint("message.nullspace.claim.step1"));
        Msg.say(player, Msg.hint("message.nullspace.claim.step2"));
        Msg.say(player, Msg.hint("message.nullspace.claim.step3"));
    }

    private void doOpenTerminal(ServerPlayer player) {
        Archive archive = registry(player.getServer()).find(player.getUUID()).orElse(null);
        if (archive == null || !AccessControl.isOwner(archive, player.getUUID())) {
            Msg.refuse(player, Msg.no("message.nullspace.terminal.owner_only"));
            return;
        }
        TerminalService.open(player);
    }

    // a saved preset name wins when given (index into the owner's presets), otherwise the raw permission mask
    public static Set<Permission> presetOrMask(ServerPlayer owner, int mask, int presetIndex) {
        if (presetIndex > 0) {
            var presets = new ArrayList<>(ownArchive(owner).presets().values());
            if (presetIndex <= presets.size()) return presets.get(presetIndex - 1);
        }
        return TerminalService.mask(mask);
    }

    @Override public Map<UUID, TempadGatewayBridge.Destination> destinations(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        ArchiveRegistry registry = registry(server);
        ServerPolicy policy = policy();
        long now = now();
        Map<UUID, TempadGatewayBridge.Destination> result = new LinkedHashMap<>();
        for (Archive archive : registry.archives().values()) {
            if (!archive.initialized() || !AccessControl.mayEnter(archive, player.getUUID(), now, policy)) continue;
            result.put(archive.owner(), destinationOf(server, archive));
        }
        return result;
    }

    private static TempadGatewayBridge.Destination destinationOf(MinecraftServer server, Archive archive) {
        String ownerName = server.getProfileCache() == null ? null
            : server.getProfileCache().get(archive.owner()).map(p -> p.getName()).orElse(null);
        Room room0 = archive.room(0);
        Vec3 position = new Vec3(room0.centerX(archive.allocation()) + 0.5, Room.FLOOR_Y + 1, room0.centerZ(archive.allocation()) + 0.5);
        Component label = ownerName == null ? Component.translatable("message.nullspace.archive_label.unknown") : Component.translatable("message.nullspace.archive_label", ownerName);
        return new TempadGatewayBridge.Destination(archive.owner(), DIMENSION, position, 0f, label);
    }


    // printing makes an item and a record each time, so the rate is capped
    private static final ActionLimiter CARD_LIMITER = new ActionLimiter(4, 100);

    private static Component cardKindName(CardGrant card) {
        return Component.translatable("gui.nullspace.card.kind." + card.kind().ordinal());
    }

    static Component printCard(ServerPlayer owner, CardGrant.Kind kind, long hours, Set<Permission> permissions, boolean holderOnly) {
        ArchiveService s = instance();
        if (throttled(CARD_LIMITER, owner)) throw new ArchiveException("error.nullspace.slow_down");
        Archive archive = ownArchive(owner);
        ServerPolicy policy = policy();
        if (!policy.cards().enabled()) throw new ArchiveException(Translatable.disabled("cards"));
        ItemStack blank = null;
        for (ItemStack stack : owner.getInventory().items) if (TempadGatewayBridge.isBlankCard(stack)) { blank = stack; break; }
        if (blank == null) for (ItemStack stack : owner.getInventory().offhand) if (TempadGatewayBridge.isBlankCard(stack)) { blank = stack; break; }
        if (blank == null) throw new ArchiveException("error.nullspace.card.need_blank");
        CardGrant card = AccessControl.printCard(archive, owner.getUUID(), UUID.randomUUID(), kind, hours * 3_600_000L, permissions, holderOnly, now(), policy);
        String ownerName = owner.getGameProfile().getName();
        Component info = kind == CardGrant.Kind.TIMED ? Component.translatable("item.nullspace.archive_card.info.timed", ownerName, hours)
            : Component.translatable("item.nullspace.archive_card.info." + (kind == CardGrant.Kind.SINGLE_VISIT ? "single" : "until_revoked"), ownerName);
        ItemStack printed = TempadGatewayBridge.printedCard(owner.getUUID(), ownerName, card.token(), info, Component.translatable("item.nullspace.archive_card"));
        if (printed == null) { // tempad's card item is missing: undo, nothing was handed out
            AccessControl.revokeCard(archive, owner.getUUID(), card.number(), now());
            AccessControl.forgetCard(archive, owner.getUUID(), card.number(), now());
            throw new ArchiveException("error.nullspace.card.need_blank");
        }
        blank.shrink(1);
        owner.getInventory().placeItemBackInInventory(printed);
        owner.getInventory().setChanged();
        s.markDirty(owner.getServer());
        LOGGER.info("Archive Card #{} ({}) printed by {} with {}", card.number(), kind, ownerName, card.permissions());
        return Msg.ok("message.nullspace.card.printed", card.number(), cardKindName(card));
    }

    static Component revokeCard(ServerPlayer owner, int number) {
        ArchiveService s = instance();
        Archive archive = ownArchive(owner);
        AccessControl.revokeCard(archive, owner.getUUID(), number, now());
        s.markDirty(owner.getServer());
        s.sweepCards(owner.getServer());
        LOGGER.info("Archive Card #{} of {} revoked", number, owner.getGameProfile().getName());
        return Msg.ok("message.nullspace.card.revoked", number);
    }

    static List<Component> cardLines(ServerPlayer owner) {
        Archive archive = ownArchive(owner);
        long now = now();
        var lines = new ArrayList<Component>();
        if (archive.cards().isEmpty()) lines.add(Msg.ok("command.nullspace.card.none"));
        archive.cards().values().stream().sorted(Comparator.comparing((CardGrant c) -> !c.live(now)).thenComparingInt(CardGrant::number)).forEach(card -> {
            Component bound = card.holderOnly() ? Component.translatable("command.nullspace.card.bound.holder")
                : card.boundTo() == null ? Component.translatable("command.nullspace.card.bound.none") : Component.translatable("command.nullspace.card.bound.to", card.boundName()); // i18n-ok: a player's name
            var names = new ArrayList<Component>();
            for (Permission p : Permission.values()) if (card.permissions().contains(p)) names.add(Component.translatable(Msg.permissionKey(p)));
            lines.add(Msg.ok("command.nullspace.card.entry", card.number(), cardKindName(card), Component.translatable("command.nullspace.card.state." + card.state(now).ordinal()), bound,
                ComponentUtils.formatList(names, Component.literal(", ")))); // i18n-ok: list separator
        });
        return lines;
    }

    static Component forgetCard(ServerPlayer owner, int number) {
        AccessControl.forgetCard(ownArchive(owner), owner.getUUID(), number, now());
        instance().markDirty(owner.getServer());
        return Msg.ok("message.nullspace.card.forgotten", number);
    }

    void sweepCards(MinecraftServer server) {
        ServerPolicy policy = policy();
        ArchiveSavedData data = null;
        boolean changed = false;
        for (Archive archive : registry(server).archives().values()) {
            if (archive.activeVisits().isEmpty()) continue;
            for (UUID id : AccessControl.cardVisitorsToEvict(archive, policy)) {
                Optional<ReturnLocation> ended = AccessControl.exit(archive, id);
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                if (data == null) data = ArchiveSavedData.get(server.overworld());
                switch (AccessControl.departure(ended, online != null, online != null && DIMENSION.equals(online.level().dimension()))) {
                    case EVICT_NOW -> evict(online, ended.orElse(null));
                    case RETURN_AT_LOGIN -> data.pendingReturns().put(id, ended.get());
                    case NONE -> { }
                }
                if (online != null && removalClearsSpawn(archive, online)) clearArchiveSpawn(online);
                LOGGER.info("Archive Card access ended for {} in the archive of {}", id, archive.owner());
                changed = true;
            }
        }
        if (changed) markDirty(server);
    }

    void sweepExpired(MinecraftServer server) {
        ServerPolicy policy = policy();
        long now = now();
        ArchiveSavedData data = null;
        boolean changed = false;
        for (Archive archive : registry(server).archives().values()) {
            for (UUID id : AccessControl.expiredVisitors(archive, now, policy)) {
                Optional<ReturnLocation> ended = AccessControl.exit(archive, id);
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                if (data == null) data = ArchiveSavedData.get(server.overworld());
                switch (AccessControl.departure(ended, online != null, online != null && DIMENSION.equals(online.level().dimension()))) {
                    case EVICT_NOW -> evict(online, ended.orElse(null), "message.nullspace.evicted.expired");
                    case RETURN_AT_LOGIN -> data.pendingReturns().put(id, ended.get());
                    case NONE -> { }
                }
                if (online != null && removalClearsSpawn(archive, online)) clearArchiveSpawn(online);
                LOGGER.info("Timed access ended for {} in the archive of {}", id, archive.owner());
                changed = true;
            }
            changed |= !AccessControl.dropExpiredInvitations(archive, now).isEmpty();
        }
        if (changed) markDirty(server);
    }

    @Override public TempadGatewayBridge.Destination cardDestination(UUID owner, UUID token) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return null;
        Archive archive = registry(server).find(owner).orElse(null);
        return archive == null || !archive.initialized() || !archive.cards().containsKey(token) ? null : destinationOf(server, archive);
    }

    @Override public UUID chooseCard(ServerPlayer player, Vec3 position, List<UUID> candidates) {
        Archive archive = registry(player.getServer()).at((int) Math.floor(position.x), (int) Math.floor(position.z)).orElse(null);
        if (archive == null) return null;
        ServerPolicy policy = policy();
        long now = now();
        UUID known = null;
        for (UUID candidate : candidates) {
            if (!archive.cards().containsKey(candidate)) continue;
            if (AccessControl.mayEnterWithCard(archive, player.getUUID(), candidate, now, policy)) return candidate;
            if (known == null) known = candidate;
        }
        return known;
    }

    // a card that belongs to somebody else names them and nothing else about the card
    private static Component refusalText(Archive archive, AccessControl.EntryRefusal refusal, UUID cardToken) {
        if (refusal == AccessControl.EntryRefusal.CARD_BOUND) {
            CardGrant card = cardToken == null ? null : archive.cards().get(cardToken);
            boolean named = card != null && card.boundName() != null && !card.boundName().isEmpty();
            return Msg.no(refusal.key(), named ? Component.literal(card.boundName()) : Component.translatable("message.nullspace.card.another_player")); // i18n-ok: a player's name
        }
        return Msg.no(refusal.key());
    }

    @Override public Component refusalFor(ServerPlayer player, UUID archiveOwner) {
        Archive archive = registry(player.getServer()).find(archiveOwner).orElse(null);
        var refusal = archive == null ? null : AccessControl.entryRefusal(archive, player.getUUID(), now(), policy());
        return refusal == null ? Msg.no("message.nullspace.gateway.no_archive") : refusalText(archive, refusal, null);
    }

    @Override public boolean archiveLinkRequired() { return ServerConfig.ARCHIVE_LINK.get(); }

    @Override public boolean isArchiveDimension(ResourceKey<Level> dimension) { return DIMENSION.equals(dimension); }

    @Override public TempadGatewayBridge.EntryDecision authorizeEntry(Entity entity, ResourceKey<Level> dimension, Vec3 position, UUID gatewayOwner) {
        return authorizeEntry(entity, dimension, position, gatewayOwner, null, null);
    }

    @Override public TempadGatewayBridge.EntryDecision authorizeEntry(Entity entity, ResourceKey<Level> dimension, Vec3 position, UUID gatewayOwner, UUID cardToken, UUID cardUser) {
        if (!(entity instanceof ServerPlayer player)) {
            if (!ServerConfig.ENTITY_TRANSPORT.get())
                return TempadGatewayBridge.EntryDecision.deny(Msg.no("message.nullspace.gateway.no_entities"));
            // Anything Tempad itself carries may follow, but only through a gateway whose opener may enter that archive right now.
            Archive target = registry(entity.getServer()).at((int) Math.floor(position.x), (int) Math.floor(position.z)).orElse(null);
            boolean openerMayEnter = target != null && gatewayOwner != null && (cardToken != null && cardUser != null
                ? AccessControl.mayEnterWithCard(target, cardUser, cardToken, now(), policy())
                : AccessControl.mayEnter(target, gatewayOwner, now(), policy()));
            if (!openerMayEnter) return TempadGatewayBridge.EntryDecision.deny(Msg.no("message.nullspace.gateway.owner_lost_access"));
            return TempadGatewayBridge.EntryDecision.allow(null);
        }
        ArchiveRegistry registry = registry(player.getServer());
        Archive archive = registry.at((int) Math.floor(position.x), (int) Math.floor(position.z)).orElse(null);
        if (archive == null) return TempadGatewayBridge.EntryDecision.deny(Msg.no("message.nullspace.gateway.no_archive"));
        var refusal = AccessControl.entryRefusal(archive, player.getUUID(), now(), policy(), cardToken);
        if (refusal != null) {
            if (cardToken != null) LOGGER.info("Archive Card refusal for {} at the archive of {}: {}", player.getGameProfile().getName(), archive.owner(), refusal.key());
            var log = refusalLog;
            if (log != null) log.put(player.getUUID(), refusal.key());
            return TempadGatewayBridge.EntryDecision.deny(refusalText(archive, refusal, cardToken));
        }
        return TempadGatewayBridge.EntryDecision.allow(archive.owner());
    }

    @Override public void beforeEntry(ServerPlayer player, UUID owner, TempadGatewayBridge.Origin origin) { beforeEntry(player, owner, origin, null); }

    @Override public void beforeEntry(ServerPlayer player, UUID owner, TempadGatewayBridge.Origin origin, UUID cardToken) {
        MinecraftServer server = player.getServer();
        Archive archive = registry(server).find(owner).orElseThrow();
        ReturnLocation returnLocation = new ReturnLocation(origin.dimension().location().toString(),
            origin.position().x, origin.position().y, origin.position().z, origin.yaw(), origin.pitch());
        boolean fresh = !archive.activeVisits().containsKey(player.getUUID());
        var visit = AccessControl.enter(archive, player.getUUID(), player.getGameProfile().getName(), returnLocation, now(), policy(), cardToken);
        markDirty(server);
        if (fresh && visit.cardToken() != null) {
            CardGrant card = archive.cards().get(visit.cardToken());
            ServerPlayer host = server.getPlayerList().getPlayer(archive.owner());
            if (host != null && card != null) Msg.say(host, Msg.ok("message.nullspace.card.entered", player.getGameProfile().getName(), card.number()));
            LOGGER.info("{} entered the archive of {} on card #{}", player.getGameProfile().getName(), archive.owner(), card == null ? "?" : card.number());
        }
    }

    // a pad door is being walked into inside the archive: owner or operator, hall installed
    @Override public Component hubCrossing(ServerPlayer player) {
        Archive archive = registry(player.getServer()).at((int) Math.floor(player.getX()), (int) Math.floor(player.getZ())).orElse(null);
        if (archive == null || !archive.basement().installed()) return Msg.disabled("pads");
        HubPolicy hub = hubPolicy();
        if (!hub.enabled()) return Msg.disabled("pads");
        return Basement.mayUse(archive, player.getUUID(), player.hasPermissions(2), policy(), hub) ? null : Msg.no("message.nullspace.pad.owner_only");
    }

    @Override public void afterExit(ServerPlayer player) {
        // the visit was already removed by AccessControl.exit when the permanent exit opened, before travel.
    }

    // where the permanent exit sends this visitor. the recorded visit is only read here and cleared when the player
    // really leaves the archive dimension (TravelGuard), so an exit gateway nobody walks into loses nothing. the
    // entry point is checked now and replaced by the nearest safe spot or the overworld spawn, not the archive
    // itself
    @Override public TempadGatewayBridge.ReturnTarget safeReturn(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        Archive archive = registry(server).at((int) Math.floor(player.getX()), (int) Math.floor(player.getZ())).orElse(null);
        var recall = archive == null ? null : RecallModule.target(archive, player);
        if (recall != null) return returnTarget(server, recall, player.getGameProfile().getName());
        var visit = archive == null ? null : archive.activeVisits().get(player.getUUID());
        return returnTarget(server, visit == null ? null : visit.returnLocation(), player.getGameProfile().getName());
    }

    @Override public void emergencyExitOpened(ServerPlayer player, TempadGatewayBridge.ReturnTarget target) {
        LOGGER.warn("Emergency archive exit used by {} (Tempad restrictions forbid the normal gateway) -> {} {}", player.getGameProfile().getName(), target.dimension().location(), target.position());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (TEAM_SWEEP.due(server.getTickCount())) sweepTeams(server);
        if (server.getTickCount() % 20 != 0) return;
        sweepExpired(server);
        sweepCards(server);
        ServerLevel archiveLevel = server.getLevel(DIMENSION);
        if (archiveLevel == null) return;
        ArchiveRegistry registry = registry(server);
        Set<UUID> occupied = new HashSet<>();
        List<ServerPlayer> strangers = new ArrayList<>();
        for (ServerPlayer player : archiveLevel.players()) {
            Archive here = registry.at((int) Math.floor(player.getX()), (int) Math.floor(player.getZ())).orElse(null);
            if (here != null) occupied.add(here.owner());
            if (!AccessControl.mayStayAt(here, player.getUUID(), now(), policy()) && !player.hasPermissions(2) && !player.isDeadOrDying()) strangers.add(player);
        }
        for (ServerPlayer player : strangers) removeStranger(registry, player);
        Set<TicketPlanner.Ticket> desired = TicketPlanner.desired(registry.archives().values(), occupied, policy());
        if (ticketLevel != archiveLevel) { currentTickets = Set.of(); ticketLevel = archiveLevel; }
        TicketPlanner.Transition transition = TicketPlanner.transition(currentTickets, desired);
        transition.acquire().forEach(ticket -> addTicket(archiveLevel, ticket));
        transition.release().forEach(ticket -> removeTicket(archiveLevel, ticket));
        currentTickets = desired;
    }

    // a same-dimension teleport can land a player in an archive they were not let into: send them back where their own visit came from, else to the fallback.
    // removals, expiry, cards and teams evict with their own message before this runs, so nobody here had their access removed
    private void removeStranger(ArchiveRegistry registry, ServerPlayer player) {
        ReturnLocation back = null;
        for (Archive other : registry.archives().values()) {
            Visit visit = other.activeVisits().get(player.getUUID());
            if (visit != null) { back = visit.returnLocation(); break; }
        }
        evict(player, back, "message.nullspace.evicted.no_gateway");
    }

    // addRegionTicket takes a distance (ticket level 33 minus distance), not a level. passing the forced ticket level
    // 31 as the distance kept a radius of about 29 chunks loaded around every anchor. vanilla force loading uses
    // distance 2, which is one chunk
    static volatile int ticketDistance = 33 - ChunkMap.FORCED_TICKET_LEVEL;

    private static void addTicket(ServerLevel level, TicketPlanner.Ticket ticket) {
        ChunkPos pos = new ChunkPos(ticket.chunk().x(), ticket.chunk().z());
        level.getChunkSource().addRegionTicket(ANCHOR_TICKET, pos, ticketDistance, pos);
    }

    private static void removeTicket(ServerLevel level, TicketPlanner.Ticket ticket) {
        ChunkPos pos = new ChunkPos(ticket.chunk().x(), ticket.chunk().z());
        level.getChunkSource().removeRegionTicket(ANCHOR_TICKET, pos, ticketDistance, pos);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        int closed = bridge.closeExits(event.getServer(), null, false);
        if (closed > 0) LOGGER.info("Closed {} open exit gateway(s) before saving (see TempadGatewayBridge.closeExits)", closed);
        ServerLevel level = event.getServer().getLevel(DIMENSION);
        if (level != null && ticketLevel == level) currentTickets.forEach(t -> removeTicket(level, t));
        currentTickets = Set.of();
    }

    int heldTicketCount() { return currentTickets.size(); }
    static ArchiveService service() { return instance(); }
    static TicketType<ChunkPos> anchorTicketType() { return ANCHOR_TICKET; }
}
