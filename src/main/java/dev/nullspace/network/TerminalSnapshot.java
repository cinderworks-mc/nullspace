package dev.nullspace.network;

import dev.nullspace.Nullspace;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// what the terminal shows. display only, the client decides nothing. status and preview text travel as translatable
// components so the client renders them in its own language; empty means nothing to say
public record TerminalSnapshot(String owner, List<RoomInfo> rooms, List<GuestInfo> guests, List<PresetInfo> presets,
                               int requested, int effective, boolean anchorInstalled, boolean anchorAllowed,
                               int maxRooms, Component message, Component preview, boolean canConfirm,
                               int maxGuests, int allowedPerms, boolean failed,
                               boolean recallInstalled, boolean recallAllowed, int recallMinutes,
                               int maxRoomWidth, int maxCeilingHeight,
                               boolean hallInstalled, boolean hallAllowed, int maxDestinations, int chrononCost, boolean hasEntry,
                               List<DestInfo> destinations, TeamInfo team, List<CardInfo> cards, CardsInfo cardSettings,
                               List<String> tunnelFinishes, int hereRoom) implements CustomPacketPayload {
    public static final Type<TerminalSnapshot> TYPE = new Type<>(Nullspace.id("terminal_snapshot"));
    // tunnel finishes: one per SurfaceGroup (floor, ceiling, walls). hereRoom is the room the viewer stands in, -1
    // in a tunnel or outside
    public static final List<String> CHARCOAL_TUNNELS = List.of("nullspace:charcoal", "nullspace:charcoal", "nullspace:charcoal");

    // for callers from before tunnels could be painted: they show charcoal tunnels
    public TerminalSnapshot(String owner, List<RoomInfo> rooms, List<GuestInfo> guests, List<PresetInfo> presets,
                            int requested, int effective, boolean anchorInstalled, boolean anchorAllowed,
                            int maxRooms, Component message, Component preview, boolean canConfirm,
                            int maxGuests, int allowedPerms, boolean failed,
                            boolean recallInstalled, boolean recallAllowed, int recallMinutes,
                            int maxRoomWidth, int maxCeilingHeight,
                            boolean hallInstalled, boolean hallAllowed, int maxDestinations, int chrononCost, boolean hasEntry,
                            List<DestInfo> destinations, TeamInfo team, List<CardInfo> cards, CardsInfo cardSettings) {
        this(owner, rooms, guests, presets, requested, effective, anchorInstalled, anchorAllowed, maxRooms, message, preview, canConfirm, maxGuests, allowedPerms, failed,
            recallInstalled, recallAllowed, recallMinutes, maxRoomWidth, maxCeilingHeight, hallInstalled, hallAllowed, maxDestinations, chrononCost, hasEntry,
            destinations, team, cards, cardSettings, CHARCOAL_TUNNELS, -1);
    }
    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalSnapshot> CODEC = StreamCodec.of((b, s) -> s.write(b), TerminalSnapshot::read);

    // doors: one bit per direction ordinal (n, e, s, w)
    // name is empty for a room the owner has not named
    public record RoomInfo(int id, int width, int height, int doors, List<String> finishes, String name) {
        public RoomInfo { if (name.length() > 64) name = name.substring(0, 64); }
        public RoomInfo(int id, int width, int height, int doors, List<String> finishes) { this(id, width, height, doors, finishes, ""); }
    }
    // kind: 0 permanent, 1 timed, 2 single visit. remainingMillis is relative to the server clock (timed only); used
    // marks a spent single visit
    public record GuestInfo(UUID id, String name, int kind, int permissions, boolean inside, long remainingMillis, boolean used) {}
    public record PresetInfo(String name, int mask) {}
    // the guests tab team row. status is the ordinal of AccessControl.TeamStatus (0 = unavailable, no row); on is
    // what the owner asked for; permissions is what they asked the team to get, effective what the server lets
    // through; mode 0 off, 1 members, 2 members and allies
    public record TeamInfo(int status, boolean on, int permissions, int effective, String name, int size, int maxSize, int mode) {
        public static final int MAX_NAME = 128;
        // a team mod may allow longer names than the packet does; cut here so one long name cannot fail the encode
        public TeamInfo { if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME); }
        public static final TeamInfo NONE = new TeamInfo(0, false, 1, 1, "", 0, 0, 0);
        public boolean present() { return status != 0; }
    }
    // kind: 0 single visit, 1 timed, 2 until revoked. state: 0 works, 1 spent, 2 expired, 3 revoked. remainingMillis
    // is relative to the server clock (timed and working only). boundName is empty until first use and for holder
    // cards
    public record CardInfo(int number, int kind, int state, long remainingMillis, int permissions, boolean holderOnly, String boundName, int uses) {
        public CardInfo { if (boundName.length() > 32) boundName = boundName.substring(0, 32); }
    }
    // what the server allows: enabled is the server switch, maxLive the cap, live how many count toward it, blank how
    // many blank location cards the owner carries, maxHours the longest timed card
    public record CardsInfo(boolean enabled, int maxLive, int live, int blank, int maxHours) {
        public static final CardsInfo NONE = new CardsInfo(false, 0, 0, 0, 1);
    }
    // slot is the pedestal it shows on (0 to 11) or -1; icon is an item id or empty. coordinates are rounded for
    // display
    public record DestInfo(int id, String name, String dimension, int x, int y, int z, String icon, int slot) {
        public DestInfo {
            if (name.length() > 64) name = name.substring(0, 64);
            if (dimension.length() > 128) dimension = dimension.substring(0, 128);
            if (icon.length() > 128) icon = icon.substring(0, 128);
        }
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private void write(RegistryFriendlyByteBuf b) {
        b.writeUtf(owner);
        b.writeVarInt(rooms.size());
        rooms.forEach(r -> { b.writeVarInt(r.id()); b.writeVarInt(r.width()); b.writeVarInt(r.height()); b.writeVarInt(r.doors()); b.writeVarInt(r.finishes().size()); r.finishes().forEach(b::writeUtf); b.writeUtf(r.name(), 64); });
        b.writeVarInt(guests.size());
        guests.forEach(g -> { b.writeUUID(g.id()); b.writeUtf(g.name()); b.writeVarInt(g.kind()); b.writeVarInt(g.permissions()); b.writeBoolean(g.inside()); b.writeLong(g.remainingMillis()); b.writeBoolean(g.used()); });
        b.writeVarInt(presets.size());
        presets.forEach(p -> { b.writeUtf(p.name()); b.writeVarInt(p.mask()); });
        b.writeVarInt(requested); b.writeVarInt(effective);
        b.writeBoolean(anchorInstalled); b.writeBoolean(anchorAllowed);
        b.writeVarInt(maxRooms);
        ComponentSerialization.STREAM_CODEC.encode(b, message); ComponentSerialization.STREAM_CODEC.encode(b, preview); b.writeBoolean(canConfirm);
        b.writeVarInt(maxGuests); b.writeVarInt(allowedPerms); b.writeBoolean(failed);
        b.writeBoolean(recallInstalled); b.writeBoolean(recallAllowed); b.writeVarInt(recallMinutes);
        b.writeVarInt(maxRoomWidth); b.writeVarInt(maxCeilingHeight);
        b.writeBoolean(hallInstalled); b.writeBoolean(hallAllowed); b.writeVarInt(maxDestinations); b.writeVarInt(chrononCost); b.writeBoolean(hasEntry);
        b.writeVarInt(destinations.size());
        destinations.forEach(d -> { b.writeVarInt(d.id()); b.writeUtf(d.name(), 64); b.writeUtf(d.dimension(), 128); b.writeInt(d.x()); b.writeInt(d.y()); b.writeInt(d.z()); b.writeUtf(d.icon(), 128); b.writeVarInt(d.slot() + 1); });
        writeTeam(b);
        b.writeVarInt(cards.size());
        cards.forEach(c -> { b.writeVarInt(c.number()); b.writeVarInt(c.kind()); b.writeVarInt(c.state()); b.writeLong(c.remainingMillis()); b.writeVarInt(c.permissions());
            b.writeBoolean(c.holderOnly()); b.writeUtf(c.boundName(), 32); b.writeVarInt(c.uses()); });
        b.writeBoolean(cardSettings.enabled()); b.writeVarInt(cardSettings.maxLive()); b.writeVarInt(cardSettings.live()); b.writeVarInt(cardSettings.blank()); b.writeVarInt(cardSettings.maxHours());
        b.writeVarInt(tunnelFinishes.size()); tunnelFinishes.forEach(f -> b.writeUtf(f, 64));
        b.writeVarInt(hereRoom + 1);
    }

    private static List<CardInfo> readCards(RegistryFriendlyByteBuf b) {
        List<CardInfo> list = new ArrayList<>();
        for (int i = Math.min(b.readVarInt(), 128); i > 0; i--) list.add(new CardInfo(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readLong(), b.readVarInt(), b.readBoolean(), b.readUtf(32), b.readVarInt()));
        return list;
    }

    private void writeTeam(RegistryFriendlyByteBuf b) {
        b.writeVarInt(team.status()); b.writeBoolean(team.on()); b.writeVarInt(team.permissions()); b.writeVarInt(team.effective());
        b.writeUtf(team.name(), TeamInfo.MAX_NAME); b.writeVarInt(team.size()); b.writeVarInt(team.maxSize()); b.writeVarInt(team.mode());
    }

    private static TeamInfo readTeam(RegistryFriendlyByteBuf b) {
        return new TeamInfo(b.readVarInt(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readUtf(TeamInfo.MAX_NAME), b.readVarInt(), b.readVarInt(), b.readVarInt());
    }

    private static TerminalSnapshot read(RegistryFriendlyByteBuf b) {
        String owner = b.readUtf();
        List<RoomInfo> rooms = new ArrayList<>();
        for (int i = b.readVarInt(); i > 0; i--) {
            int id = b.readVarInt(), width = b.readVarInt(), height = b.readVarInt(), doors = b.readVarInt();
            List<String> finishes = new ArrayList<>();
            for (int f = b.readVarInt(); f > 0; f--) finishes.add(b.readUtf());
            rooms.add(new RoomInfo(id, width, height, doors, finishes, b.readUtf(64)));
        }
        List<GuestInfo> guests = new ArrayList<>();
        for (int i = b.readVarInt(); i > 0; i--) guests.add(new GuestInfo(b.readUUID(), b.readUtf(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readLong(), b.readBoolean()));
        List<PresetInfo> presets = new ArrayList<>();
        for (int i = b.readVarInt(); i > 0; i--) presets.add(new PresetInfo(b.readUtf(), b.readVarInt()));
        return new TerminalSnapshot(owner, rooms, guests, presets, b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readBoolean(),
            b.readVarInt(), ComponentSerialization.STREAM_CODEC.decode(b), ComponentSerialization.STREAM_CODEC.decode(b), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readBoolean(),
            b.readBoolean(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
            b.readBoolean(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readBoolean(), readDests(b), readTeam(b),
            readCards(b), new CardsInfo(b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()), readTunnels(b), b.readVarInt() - 1);
    }

    private static List<String> readTunnels(RegistryFriendlyByteBuf b) {
        List<String> list = new ArrayList<>();
        for (int i = Math.min(b.readVarInt(), 8); i > 0; i--) list.add(b.readUtf(64));
        return list;
    }

    private static List<DestInfo> readDests(RegistryFriendlyByteBuf b) {
        List<DestInfo> list = new ArrayList<>();
        for (int i = Math.min(b.readVarInt(), 64); i > 0; i--) list.add(new DestInfo(b.readVarInt(), b.readUtf(64), b.readUtf(128), b.readInt(), b.readInt(), b.readInt(), b.readUtf(128), b.readVarInt() - 1));
        return list;
    }
}
