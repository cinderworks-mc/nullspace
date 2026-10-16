package dev.nullspace.server;

import com.mojang.authlib.GameProfile;
import dev.nullspace.content.Content;
import dev.nullspace.core.AccessControl;
import dev.nullspace.core.ActionLimiter;
import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.CardGrant;
import dev.nullspace.core.Direction;
import dev.nullspace.core.GuestLookup;
import dev.nullspace.core.Invitation;
import dev.nullspace.core.LayoutPlanner;
import dev.nullspace.core.Permission;
import dev.nullspace.core.Room;
import dev.nullspace.core.ServerPolicy;
import dev.nullspace.core.Surface;
import dev.nullspace.core.SurfaceGroup;
import dev.nullspace.integration.tempad.TempadGatewayBridge;
import dev.nullspace.network.TerminalAction;
import dev.nullspace.network.TerminalNetwork;
import dev.nullspace.network.TerminalSnapshot;
import dev.nullspace.text.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TerminalService {
    private TerminalService() {}

    private static final Logger LOG = LoggerFactory.getLogger("nullspace");

    // requests over this rate are dropped: each accepted one can scan player data, load chunks, write a log line or
    // send a snapshot
    private static final ActionLimiter LIMITER = new ActionLimiter(10, 20);
    private static final ActionLimiter SELECT_LIMITER = new ActionLimiter(10, 20);

    public static void select(ServerPlayer player, String finish) {
        if (!SELECT_LIMITER.tryAcquire(player.getUUID(), player.getServer().getTickCount())) return;
        ArchiveService.selectFinish(player, finish);
    }

    public static void open(ServerPlayer player) { ArchiveService.play(player, Content.TERMINAL_OPEN); send(player, Component.empty(), Component.empty(), false, false); }

    public static void handle(ServerPlayer player, TerminalAction action) {
        if (!LIMITER.tryAcquire(player.getUUID(), player.getServer().getTickCount())) return;
        Component message = Component.empty(), preview = Component.empty();
        boolean confirm = false, failed = false;
        try {
            Archive archive = ArchiveService.ownArchive(player);
            if (!ArchiveService.DIMENSION.equals(player.level().dimension()) || !archive.allocation().contains((int) Math.floor(player.getX()), (int) Math.floor(player.getZ())))
                throw new ArchiveException("error.nullspace.terminal.outside");
            switch (action.kind()) {
                case REFRESH -> {}
                case INVITE -> {
                    if (action.text().isBlank()) throw new ArchiveException("error.nullspace.invite.blank_name");
                    GameProfile guest = profile(player.getServer(), action.text().trim());
                    long hours = action.c();
                    Invitation.Kind kind = Invitation.Kind.values()[Math.floorMod(action.b() & 0xFF, 3)];
                    Set<Permission> perms = ArchiveService.presetOrMask(player, action.b() >> 8, action.a());
                    message = ArchiveService.invite(player, guest, kind, hours, perms);
                }
                case SET_PERMISSIONS -> {
                    GameProfile guest = guest(player.getServer(), archive, action.text());
                    message = ArchiveService.setPermissions(player, guest, mask(action.a()));
                }
                case REMOVE -> {
                    message = ArchiveService.remove(player, guest(player.getServer(), archive, action.text()));
                }
                case PREVIEW_EXPAND -> { var p = RoomOperations.previewExpansion(player, action.a(), axis(action.b())); preview = p.text(); confirm = true; }
                case EXPAND -> { message = RoomOperations.expand(player, action.a(), axis(action.b())); Msg.say(player, message); }
                case PREVIEW_ANNEX -> { var p = RoomOperations.previewAnnex(player, action.a(), Direction.values()[Math.floorMod(action.b(), 4)]); preview = p.text(); confirm = true; }
                case ANNEX -> { message = RoomOperations.annex(player, action.a(), Direction.values()[Math.floorMod(action.b(), 4)]); Msg.say(player, message); }
                case INSTALL_RECALL -> message = RecallModule.installFromInventory(player);
                case INSTALL_HALL -> message = WaypointHall.installFromInventory(player);
                case DEST_CAPTURE -> message = HubDestinations.captureEntry(player, action.text());
                case DEST_IMPORT -> message = HubDestinations.importCard(player, action.text());
                case DEST_RENAME -> message = HubDestinations.rename(player, action.a(), action.text());
                case DEST_DELETE -> message = HubDestinations.delete(player, action.a());
                case DEST_BIND -> message = HubDestinations.bind(player, action.a(), action.b() - 1);
                case DEST_ICON -> message = HubDestinations.icon(player, action.a(), action.b());
                case SETTING -> message = ArchiveService.setSetting(player, action.text(), action.a() != 0);
                case SURFACE -> { ArchiveService.paintSurface(player, action.a(), action.b(), action.text()); message = Msg.ok("message.nullspace.surface.repainted"); }
                case SURFACE_ALL -> { ArchiveService.paintEveryRoom(player, action.a(), action.text()); message = Msg.ok("message.nullspace.surface.repainted_all", archive.rooms().size()); }
                case RENAME_ROOM -> message = ArchiveService.renameRoom(player, action.a(), action.text());
                case SURFACE_TUNNELS -> { ArchiveService.paintTunnels(player, action.a(), action.text()); message = Msg.ok("message.nullspace.surface.repainted_tunnels"); }
                case TEAM_TOGGLE -> message = ArchiveService.setTeamAccess(player, action.a() != 0);
                case TEAM_PERMISSIONS -> message = ArchiveService.setTeamPermissions(player, mask(action.a()));
                case PRINT_CARD -> {
                    int kind = action.b() & 3;
                    if (kind > 2) throw new ArchiveException("error.nullspace.failed", "card kind");
                    message = ArchiveService.printCard(player, CardGrant.Kind.values()[kind], action.c(), mask(action.a()), (action.b() & 4) != 0);
                }
                case REVOKE_CARD -> message = ArchiveService.revokeCard(player, action.a());
                case FORGET_CARD -> message = ArchiveService.forgetCard(player, action.a());
                case SAVE_PRESET -> {
                    if (action.text().isBlank()) throw new ArchiveException("error.nullspace.preset.blank_name");
                    message = ArchiveService.savePreset(player, action.text().trim(), mask(action.a())); }
            }
        } catch (SecurityException | IllegalArgumentException e) {
            message = Msg.of(e);
            failed = true;
            ArchiveService.play(player, Content.TERMINAL_DENY);
        } catch (RuntimeException e) {
            // the action comes from a packet: a malformed one must refuse and be logged, not drop the connection
            LOG.warn("Terminal action {} from {} failed unexpectedly", action.kind(), player.getGameProfile().getName(), e);
            message = Msg.of(e);
            failed = true;
        }
        if (action.kind() != TerminalAction.Kind.REFRESH) LOG.debug("Terminal action {} text='{}' a={} b={} c={} by {} -> {}: {}", action.kind(), action.text(), action.a(), action.b(), action.c(),
            player.getGameProfile().getName(), failed ? "REFUSED" : "OK", message.getString().isEmpty() ? "(preview)" : message.getString());
        if (!failed && !message.getString().isEmpty()) ArchiveService.play(player, action.kind() == TerminalAction.Kind.EXPAND || action.kind() == TerminalAction.Kind.ANNEX
            ? Content.ROOM_CHANGE : Content.TERMINAL_CONFIRM);
        send(player, message, preview, confirm, failed);
    }

    // the axis the terminal asks for: b is 0 for width (all it could mean before ceilings) and 1 for height
    static LayoutPlanner.Axis axis(int b) { return Math.floorMod(b, 2) == 1 ? LayoutPlanner.Axis.HEIGHT : LayoutPlanner.Axis.WIDTH; }

    public static Set<Permission> mask(int bits) {
        Set<Permission> set = EnumSet.of(Permission.ENTER);
        for (Permission p : Permission.values()) if (p != Permission.ADMIN && (bits & (1 << p.ordinal())) != 0) set.add(p);
        return set;
    }

    private static int bits(Set<Permission> set) {
        int bits = 0;
        for (Permission p : set) bits |= 1 << p.ordinal();
        return bits;
    }

    private static final long NAMES_RESCAN_NANOS = 2_000_000_000L;
    private static Map<String, GameProfile> savedNames = Map.of();
    private static long savedNamesAt = Long.MIN_VALUE;
    private static WeakReference<MinecraftServer> savedNamesServer = new WeakReference<>(null);

    // a miss rescans the directory, but at most once every couple of seconds, not once per lookup
    private static GameProfile savedPlayer(MinecraftServer server, String name) {
        if (savedNamesServer.get() != server) { savedNames = Map.of(); savedNamesAt = Long.MIN_VALUE; savedNamesServer = new WeakReference<>(server); }
        String key = name.toLowerCase(Locale.ROOT);
        GameProfile hit = savedNames.get(key);
        long now = System.nanoTime();
        if (hit != null || (savedNamesAt != Long.MIN_VALUE && now - savedNamesAt < NAMES_RESCAN_NANOS)) return hit;
        savedNamesAt = now;
        savedNames = scanPlayers(server);
        return savedNames.get(key);
    }

    private static Map<String, GameProfile> scanPlayers(MinecraftServer server) {
        var cache = server.getProfileCache();
        var dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile().listFiles((d, f) -> f.endsWith(".dat"));
        Map<String, GameProfile> found = new HashMap<>();
        if (cache != null && dir != null) for (var file : dir) {
            try {
                cache.get(UUID.fromString(file.getName().substring(0, file.getName().length() - 4)))
                    .ifPresent(profile -> found.putIfAbsent(profile.getName().toLowerCase(Locale.ROOT), profile));
            } catch (IllegalArgumentException ignored) { }
        }
        return found;
    }

    static GameProfile profile(MinecraftServer server, String name) {
        // only players this server has actually seen. The cache's own name lookup is not used: on offline-mode servers it invents a profile for any name.
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getGameProfile();
        GameProfile saved = savedPlayer(server, name);
        if (saved != null) return saved;
        throw new ArchiveException("error.nullspace.player.unknown", name);
    }

    static GameProfile guest(MinecraftServer server, Archive archive, String text) {
        var cache = server.getProfileCache();
        Map<UUID, String> names = new HashMap<>();
        for (var id : archive.invitations().keySet()) names.put(id, guestName(cache, id));
        var found = GuestLookup.invited(names, text);
        if (found.isPresent()) return new GameProfile(found.get(), names.get(found.get()));
        return profile(server, text.trim());
    }

    static String guestName(GameProfileCache cache, UUID id) {
        return cache == null ? id.toString() : cache.get(id).map(GameProfile::getName).orElse(id.toString());
    }

    private static List<TerminalSnapshot.DestInfo> destinations(Archive archive) {
        List<TerminalSnapshot.DestInfo> list = new ArrayList<>();
        for (var d : archive.basement().destinations().values())
            list.add(new TerminalSnapshot.DestInfo(d.id(), d.name(), d.dimension(), (int) Math.floor(d.x()), (int) Math.floor(d.y()), (int) Math.floor(d.z()), d.icon(), archive.basement().slotOf(d.id())));
        return list;
    }

    private static TerminalSnapshot.TeamInfo teamInfo(Archive archive, ServerPolicy policy) {
        var view = AccessControl.teamView(archive, policy);
        if (view.status() == AccessControl.TeamStatus.UNAVAILABLE) return TerminalSnapshot.TeamInfo.NONE;
        var access = archive.teamAccess();
        Set<Permission> effective = EnumSet.noneOf(Permission.class);
        for (Permission p : access.permissions()) if (policy.allowedGuestPermissions().contains(p)) effective.add(p);
        return new TerminalSnapshot.TeamInfo(view.status().ordinal(), access.enabled(), bits(access.permissions()), bits(effective),
            view.teamName() == null ? "" : view.teamName(), view.size(), policy.teams().maxTeamSize(), policy.teams().mode().ordinal());
    }

    private static List<TerminalSnapshot.CardInfo> cardInfos(Archive archive, long now) {
        List<TerminalSnapshot.CardInfo> list = new ArrayList<>();
        for (var card : archive.cards().values()) {
            var state = card.state(now);
            list.add(new TerminalSnapshot.CardInfo(card.number(), card.kind().ordinal(), state.ordinal(),
                card.kind() == CardGrant.Kind.TIMED && state == CardGrant.State.LIVE ? Math.max(0, card.expiresAtMillis() - now) : 0,
                bits(card.permissions()), card.holderOnly(), card.boundName() == null ? "" : card.boundName(), card.uses()));
        }
        // cards that work first, then the others, each group in print order.
        list.sort(Comparator.comparing((TerminalSnapshot.CardInfo c) -> c.state() != 0).thenComparingInt(TerminalSnapshot.CardInfo::number));
        return list;
    }

    private static TerminalSnapshot.CardsInfo cardsInfo(ServerPlayer player, Archive archive, ServerPolicy policy, long now) {
        int blank = 0;
        for (var stack : player.getInventory().items) if (TempadGatewayBridge.isBlankCard(stack)) blank += stack.getCount();
        for (var stack : player.getInventory().offhand) if (TempadGatewayBridge.isBlankCard(stack)) blank += stack.getCount();
        return new TerminalSnapshot.CardsInfo(policy.cards().enabled(), policy.cards().maxLive(), AccessControl.liveCards(archive, now), blank,
            (int) Math.max(1, Math.min(87600, policy.maxInvitationDurationMillis() / 3_600_000L)));
    }

    private static int hereRoom(ServerPlayer player, Archive archive) {
        if (!ArchiveService.DIMENSION.equals(player.level().dimension())) return -1;
        var pos = player.blockPosition();
        return archive.roomAt(pos.getX(), pos.getY(), pos.getZ()).map(Room::id).orElse(-1);
    }

    private static List<String> tunnelFinishes(Archive archive) {
        List<String> list = new ArrayList<>();
        for (SurfaceGroup group : SurfaceGroup.values()) list.add(archive.tunnelFinish(group));
        return list;
    }

    private static void send(ServerPlayer player, Component message, Component preview, boolean confirm, boolean failed) {
        Archive archive = ArchiveService.registryOf(player.getServer()).find(player.getUUID()).orElse(null);
        if (archive == null || !archive.initialized()) { Msg.refuse(player, Msg.no("error.nullspace.no_archive")); return; }
        var policy = ArchiveService.currentPolicy();
        List<TerminalSnapshot.RoomInfo> rooms = new ArrayList<>();
        for (Room r : archive.rooms().values()) {
            int doors = 0;
            for (Direction d : r.connections().keySet()) doors |= 1 << d.ordinal();
            List<String> finishes = new ArrayList<>();
            for (Surface surface : Surface.values()) finishes.add(r.finishes().get(surface));
            rooms.add(new TerminalSnapshot.RoomInfo(r.id(), r.width(), r.height(), doors, finishes, r.name()));
        }
        List<TerminalSnapshot.GuestInfo> guests = new ArrayList<>();
        var cache = player.getServer().getProfileCache();
        long now = ArchiveService.now();
        archive.invitations().forEach((id, inv) -> { if (inv.kind() != Invitation.Kind.TIMED || inv.expiresAtMillis() > now) guests.add(new TerminalSnapshot.GuestInfo(id,
            guestName(cache, id), inv.kind().ordinal(), bits(inv.permissions()),
            archive.activeVisits().containsKey(id), inv.kind() == Invitation.Kind.TIMED ? inv.expiresAtMillis() - now : 0, inv.consumed())); });
        guests.sort(Comparator.comparing((TerminalSnapshot.GuestInfo g) -> !g.inside()).thenComparing(g -> g.name().toLowerCase(Locale.ROOT)));
        List<TerminalSnapshot.PresetInfo> presets = new ArrayList<>();
        archive.presets().forEach((name, perms) -> presets.add(new TerminalSnapshot.PresetInfo(name, bits(perms))));
        var req = archive.settings();
        var eff = policy.effective(req);
        boolean grave = DeathAndSpawn.graveModLoaded();
        int requested = (req.keepInventory() ? 1 : 0) | (req.keepXp() ? 2 : 0) | (req.allowSpawn() ? 4 : 0);
        int effective = ((eff.keepInventory() && !grave) ? 1 : 0) | ((eff.keepXp() && !grave) ? 2 : 0) | (eff.allowSpawn() ? 4 : 0);
        TerminalNetwork.send(player, new TerminalSnapshot(player.getGameProfile().getName(), rooms, guests, presets,
            requested, effective, archive.anchorInstalled(), policy.allowAnchors(), policy.maxRooms(), message, preview, confirm,
            policy.maxGuests(), bits(policy.allowedGuestPermissions()), failed,
            archive.recallInstalled(), policy.allowRecall(), policy.recallMinutes(),
            policy.maxRoomWidth(), policy.maxCeilingHeight(),
            archive.basement().installed(), ArchiveService.hubPolicy().enabled(), ArchiveService.hubPolicy().maxDestinations(), ServerConfig.HUB_CHRONON_COST.get(),
            archive.activeVisits().containsKey(player.getUUID()), destinations(archive), teamInfo(archive, policy),
            cardInfos(archive, ArchiveService.now()), cardsInfo(player, archive, policy, ArchiveService.now()), tunnelFinishes(archive), hereRoom(player, archive)));
    }
}
