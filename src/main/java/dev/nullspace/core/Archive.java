package dev.nullspace.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class Archive {
    private final UUID owner;
    private final Allocation allocation;
    private final String starterPreset;
    private boolean initialized;
    private boolean anchorInstalled;
    private boolean recallInstalled;
    private RecallPoint recall;
    private final Basement basement = new Basement();
    private ArchiveSettings settings = ArchiveSettings.DEFAULT;
    private final Map<Integer, Room> rooms = new LinkedHashMap<>();
    private final Map<UUID, Invitation> invitations = new HashMap<>();
    private final Map<UUID, Visit> visits = new HashMap<>();
    private final Map<String, Set<Permission>> presets = new LinkedHashMap<>();
    private TeamAccess teamAccess = TeamAccess.OFF;
    private final Map<UUID, CardGrant> cards = new LinkedHashMap<>();
    private int nextCardNumber = 1;
    // tunnels have no record of their own, so their finish lives here. older saves have none and read as charcoal
    public static final String DEFAULT_TUNNEL_FINISH = "nullspace:charcoal";
    private final EnumMap<SurfaceGroup, String> tunnelFinishes = new EnumMap<>(SurfaceGroup.class);

    public Archive(UUID owner, Allocation allocation, String starterPreset) {
        this.owner = Objects.requireNonNull(owner); this.allocation = Objects.requireNonNull(allocation);
        if (starterPreset == null || !starterPreset.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw ArchiveException.corrupt("starter-preset");
        this.starterPreset = starterPreset;
        rooms.put(0, new Room(0, 0, 0, 0));
        for (SurfaceGroup g : SurfaceGroup.values()) tunnelFinishes.put(g, DEFAULT_TUNNEL_FINISH);
    }
    public static Archive restore(UUID owner, Allocation allocation, String starterPreset, boolean initialized,
                                  boolean anchorInstalled, ArchiveSettings settings, Map<Integer, Room> rooms,
                                  Map<UUID, Invitation> invitations, Map<UUID, Visit> visits,
                                  Map<String, Set<Permission>> presets) {
        Archive archive = new Archive(owner, allocation, starterPreset);
        if (!rooms.containsKey(0) || rooms.size() > 256) throw ArchiveException.corrupt("room-collection");
        for (Map.Entry<Integer, Room> entry : rooms.entrySet()) {
            Room room = entry.getValue();
            if (entry.getKey() != room.id()) throw ArchiveException.corrupt("room-key");
            for (Room other : rooms.values()) {
                if (room.id() != other.id() && room.reservedBounds(allocation).intersects(other.reservedBounds(allocation))) {
                    throw ArchiveException.corrupt("room-overlap");
                }
            }
            room.connections().forEach((direction, neighborId) -> {
                Room neighbor = rooms.get(neighborId);
                if (neighbor == null || !Integer.valueOf(room.id()).equals(neighbor.connections().get(direction.opposite()))
                    || neighbor.gridX() != room.gridX() + direction.dx || neighbor.gridZ() != room.gridZ() + direction.dz) {
                    throw ArchiveException.corrupt("room-connection");
                }
            });
        }
        if (invitations.containsKey(owner)) throw ArchiveException.corrupt("owner-guest");
        visits.forEach((visitor, visit) -> {
            if (!visitor.equals(owner) && visit.permissions().contains(Permission.ADMIN)) throw ArchiveException.corrupt("guest-admin");
        });
        archive.rooms.clear(); archive.rooms.putAll(rooms);
        archive.invitations.putAll(invitations); archive.visits.putAll(visits);
        presets.forEach((name, permissions) -> archive.putPreset(name, permissions));
        archive.initialized = initialized; archive.anchorInstalled = anchorInstalled;
        archive.settings = Objects.requireNonNull(settings);
        return archive;
    }
    public UUID owner() { return owner; }
    public Allocation allocation() { return allocation; }
    public long allocationId() { return allocation.id(); }
    public int originX() { return allocation.originX(); }
    public int originZ() { return allocation.originZ(); }
    public String starterPreset() { return starterPreset; }
    public boolean initialized() { return initialized; }
    // call after the starter room is placed, and save in the same step
    public void markInitialized() { initialized = true; }
    public boolean anchorInstalled() { return anchorInstalled; }
    public boolean recallInstalled() { return recallInstalled; }
    public RecallPoint recallPoint() { return recall; }
    public ArchiveSettings settings() { return settings; }
    public Basement basement() { return basement; }
    public Map<Integer, Room> rooms() { return Collections.unmodifiableMap(rooms); }
    public Map<UUID, Invitation> invitations() { return Collections.unmodifiableMap(invitations); }
    public Map<UUID, Visit> activeVisits() { return Collections.unmodifiableMap(visits); }
    public TeamAccess teamAccess() { return teamAccess; }
    public Map<UUID, CardGrant> cards() { return Collections.unmodifiableMap(cards); }
    public int nextCardNumber() { return nextCardNumber; }
    public Map<String, Set<Permission>> presets() { return Collections.unmodifiableMap(presets); }
    public Map<SurfaceGroup, String> tunnelFinishes() { return Collections.unmodifiableMap(tunnelFinishes); }
    public String tunnelFinish(SurfaceGroup group) { return tunnelFinishes.get(group); }
    public Room room(int id) {
        Room room = rooms.get(id);
        if (room == null) throw new ArchiveException("error.nullspace.room.unknown");
        return room;
    }
    // the room whose shell holds the spot (walls and doorways count), or none in a tunnel or outside the rooms
    public Optional<Room> roomAt(int x, int y, int z) {
        for (Room room : rooms.values()) if (room.shell(allocation).contains(x, y, z)) return Optional.of(room);
        return Optional.empty();
    }
    public Room renameRoom(UUID actor, int id, String name) { requireOwner(actor); Room room = room(id); room.setName(name); return room; }
    public void requireOwner(UUID actor) { if (!owner.equals(actor)) throw new ArchiveSecurityException("error.nullspace.owner_only"); }
    public void updateSettings(UUID actor, ArchiveSettings settings) { requireOwner(actor); this.settings = Objects.requireNonNull(settings); }
    public void installAnchor(UUID actor) { requireOwner(actor); anchorInstalled = true; }
    public void installRecall(UUID actor) { requireOwner(actor); recallInstalled = true; }
    public void installBasement(UUID actor) { requireOwner(actor); basement.markInstalled(); }
    public Destination addDestination(UUID actor, String name, String dimension, double x, double y, double z, float yaw, String icon, int max) {
        requireOwner(actor); return basement.add(name, dimension, x, y, z, yaw, icon, max);
    }
    public Destination renameDestination(UUID actor, int id, String name) { requireOwner(actor); return basement.rename(id, name); }
    public Destination setDestinationIcon(UUID actor, int id, String icon) { requireOwner(actor); return basement.setIcon(id, icon); }
    public Destination removeDestination(UUID actor, int id) { requireOwner(actor); return basement.remove(id); }
    public void bindPad(UUID actor, int slot, int destinationId) { requireOwner(actor); basement.bind(slot, destinationId); }
    public void unbindPad(UUID actor, int slot) { requireOwner(actor); basement.unbind(slot); }
    public int restoreBasement(boolean installed, int nextDestinationId, List<Destination> destinations, Map<Integer, Integer> bindings) {
        return basement.restore(installed, nextDestinationId, destinations, bindings);
    }
    void putTeamAccess(TeamAccess access) { teamAccess = Objects.requireNonNull(access); }
    public void restoreTeamAccess(TeamAccess access) { putTeamAccess(access); }
    int takeCardNumber() { return nextCardNumber++; }
    void putCard(CardGrant card) { cards.put(card.token(), card); }
    void removeCard(UUID token) { cards.remove(token); }
    public int restoreCards(int next, List<CardGrant> saved) {
        int dropped = 0, max = 0;
        Set<Integer> numbers = new HashSet<>();
        cards.clear();
        for (CardGrant card : saved) {
            if (cards.containsKey(card.token()) || !numbers.add(card.number()) || cards.size() >= CardPolicy.MAX_RECORDS) { dropped++; continue; }
            cards.put(card.token(), card);
            max = Math.max(max, card.number());
        }
        nextCardNumber = Math.max(Math.max(1, next), max + 1);
        return dropped;
    }
    void setRecall(RecallPoint point) { recall = point; }
    public void restoreRecall(boolean installed, RecallPoint point) { recallInstalled = installed; recall = installed ? point : null; }
    // the same finish on that part of every room. a surface set as a whole drops its per-block paint, so every room
    // ends up looking the same
    public void paintEveryRoom(UUID actor, SurfaceGroup group, String finish) {
        requireOwner(actor);
        for (Room room : rooms.values()) for (Surface surface : group.surfaces()) room.setSurfaceFinish(surface, finish);
    }
    public void setTunnelFinish(UUID actor, SurfaceGroup group, String finish) { requireOwner(actor); putTunnelFinish(group, finish); }
    public void restoreTunnelFinishes(Map<SurfaceGroup, String> saved) { saved.forEach(this::putTunnelFinish); }
    private void putTunnelFinish(SurfaceGroup group, String finish) {
        if (finish == null || !finish.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new ArchiveException("error.nullspace.finish.invalid");
        tunnelFinishes.put(Objects.requireNonNull(group), finish);
    }
    public void savePreset(UUID actor, String name, Set<Permission> permissions) { requireOwner(actor); putPreset(name, permissions); }
    private void putPreset(String name, Set<Permission> permissions) {
        if (name == null || !name.matches("[A-Za-z0-9 _.-]{1,48}")) throw new ArchiveException("error.nullspace.preset.bad_name");
        if (presets.size() >= 32 && !presets.containsKey(name)) throw new ArchiveException("error.nullspace.preset.limit", 32);
        presets.put(name, Invitation.guestPermissions(permissions));
    }
    void putRoom(Room room) { if (rooms.putIfAbsent(room.id(), room) != null) throw ArchiveException.corrupt("room-exists"); }
    void removeRoom(int id) { rooms.remove(id); }
    void putInvitation(UUID guest, Invitation invitation) { invitations.put(guest, invitation); }
    void removeInvitation(UUID guest) { invitations.remove(guest); }
    void putVisit(UUID visitor, Visit visit) { visits.put(visitor, visit); }
    Visit removeVisit(UUID visitor) { return visits.remove(visitor); }
}
