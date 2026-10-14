package dev.nullspace.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

// the waypoint hall: a sunken 13 x 13 hall under the starter room, made once by installing a waypoint module. not a
// room (no room limit, no widening, no doorways). coordinates are relative to the starter room centre
public final class Basement {
    public static final int FLOOR_Y = Room.FLOOR_Y - 8;           // 56: the floor shell
    public static final int CLEAR_HEIGHT = Room.CLEAR_HEIGHT;      // 6
    public static final int WIDTH = 13;                            // fixed interior width
    public static final int RADIUS = WIDTH / 2;                    // 6
    public static final int CEILING_Y = FLOOR_Y + CLEAR_HEIGHT + 1;
    public static final int INTERIOR_MIN_Y = FLOOR_Y + 1, INTERIOR_MAX_Y = FLOOR_Y + CLEAR_HEIGHT;
    public static final int SLOT_COUNT = 12;
    public static final int MAX_DESTINATIONS = HubPolicy.MAX_DESTINATIONS;

    private static final int[][] SLOTS = {{-3, -4}, {0, -4}, {3, -4}, {4, -3}, {4, 0}, {4, 3}, {3, 4}, {0, 4}, {-3, 4}, {-4, 3}, {-4, 0}, {-4, -3}};
    private static final int[][] PILLARS = {{-4, -4}, {4, -4}, {4, 4}, {-4, 4}};
    public static final int TERMINAL_U = 0, TERMINAL_W = -RADIUS, EXIT_U = -1, EXIT_W = -RADIUS;

    public static final String WALL_FINISH = "nullspace:ivory", FLOOR_FINISH = "nullspace:charcoal", CEILING_FINISH = "nullspace:charcoal",
        LENS_FINISH = "nullspace:amber";

    public enum Cell { SHELL, LIFT_COLUMN, SLOT, PILLAR, TERMINAL, EXIT, DAIS, FREE }

    private boolean installed;
    private int nextDestinationId = 1;
    private final Map<Integer, Destination> destinations = new LinkedHashMap<>();
    private final Map<Integer, Integer> bindings = new LinkedHashMap<>();   // slot -> destination id


    public static int centerX(Allocation a) { return a.originX(); }
    public static int centerZ(Allocation a) { return a.originZ(); }
    public static Bounds shell(Allocation a) { return box(a, RADIUS + 1, FLOOR_Y, CEILING_Y); }
    public static Bounds interior(Allocation a) { return box(a, RADIUS, INTERIOR_MIN_Y, INTERIOR_MAX_Y); }
    private static Bounds box(Allocation a, int r, int y0, int y1) { return new Bounds(a.originX() - r, y0, a.originZ() - r, a.originX() + r, y1, a.originZ() + r); }

    public static BlockPoint upperPad(Allocation a) { return new BlockPoint(a.originX(), Room.FLOOR_Y, a.originZ()); }
    public static BlockPoint lowerPad(Allocation a) { return new BlockPoint(a.originX(), FLOOR_Y, a.originZ()); }
    public static BlockPoint upperLanding(Allocation a) { return new BlockPoint(a.originX(), Room.FLOOR_Y + 1, a.originZ()); }
    public static BlockPoint lowerLanding(Allocation a) { return new BlockPoint(a.originX(), INTERIOR_MIN_Y, a.originZ()); }

    public static BlockPoint slot(Allocation a, int slot) {
        int[] o = SLOTS[Math.floorMod(slot, SLOT_COUNT)];
        return new BlockPoint(a.originX() + o[0], INTERIOR_MIN_Y, a.originZ() + o[1]);
    }
    public static List<BlockPoint> slots(Allocation a) {
        List<BlockPoint> list = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) list.add(slot(a, i));
        return list;
    }
    public static Direction slotFacing(int slot) {
        int[] o = SLOTS[Math.floorMod(slot, SLOT_COUNT)];
        if (Math.abs(o[1]) == 4 && Math.abs(o[0]) != 4) return o[1] < 0 ? Direction.SOUTH : Direction.NORTH;
        return o[0] > 0 ? Direction.WEST : Direction.EAST;
    }
    public static List<BlockPoint> pillars(Allocation a) {
        List<BlockPoint> list = new ArrayList<>();
        for (int[] o : PILLARS) for (int y = INTERIOR_MIN_Y; y <= INTERIOR_MAX_Y; y++) list.add(new BlockPoint(a.originX() + o[0], y, a.originZ() + o[1]));
        return list;
    }
    public static BlockPoint terminal(Allocation a) { return new BlockPoint(a.originX() + TERMINAL_U, INTERIOR_MIN_Y, a.originZ() + TERMINAL_W); }
    public static BlockPoint exit(Allocation a) { return new BlockPoint(a.originX() + EXIT_U, INTERIOR_MIN_Y, a.originZ() + EXIT_W); }

    public static BlockPoint exitUpper(Allocation a) { return new BlockPoint(a.originX() + EXIT_U, INTERIOR_MIN_Y + 1, a.originZ() + EXIT_W); }

    public static Cell classify(int u, int y, int w) {
        if (Math.abs(u) > RADIUS + 1 || Math.abs(w) > RADIUS + 1 || y < FLOOR_Y || y > CEILING_Y) return Cell.FREE;
        boolean onShell = Math.abs(u) == RADIUS + 1 || Math.abs(w) == RADIUS + 1 || y == FLOOR_Y || y == CEILING_Y;
        if (y == FLOOR_Y && Math.abs(u) <= 1 && Math.abs(w) <= 1 && !(u == 0 && w == 0)) return Cell.DAIS;
        if (y == FLOOR_Y) for (int[] s : SLOTS) if (s[0] == u && s[1] == w) return Cell.SLOT;   // the pedestal base under a slot
        if (onShell) return Cell.SHELL;
        if (Math.abs(u) <= 1 && Math.abs(w) <= 1) return Cell.LIFT_COLUMN;
        for (int[] p : PILLARS) if (p[0] == u && p[1] == w) return Cell.PILLAR;
        if (y == INTERIOR_MIN_Y + 1 && u == EXIT_U && w == EXIT_W) return Cell.EXIT;
        if (y == INTERIOR_MIN_Y) {
            for (int[] s : SLOTS) if (s[0] == u && s[1] == w) return Cell.SLOT;
            if (u == TERMINAL_U && w == TERMINAL_W) return Cell.TERMINAL;
            if (u == EXIT_U && w == EXIT_W) return Cell.EXIT;
        }
        return Cell.FREE;
    }

    public static int slotAt(int originX, int originZ, int x, int y, int z) {
        if (y != INTERIOR_MIN_Y) return -1;
        for (int i = 0; i < SLOT_COUNT; i++) if (SLOTS[i][0] == x - originX && SLOTS[i][1] == z - originZ) return i;
        return -1;
    }
    public static int slotAtNearest(int x, int y, int z) {
        int ox = Math.round(x / (float) Allocation.SPACING) * Allocation.SPACING, oz = Math.round(z / (float) Allocation.SPACING) * Allocation.SPACING;
        return slotAt(ox, oz, x, y, z);
    }
    // the 3 x 3 column above the hall pad has to stay free
    public static boolean inLiftColumn(Allocation a, int x, int y, int z) {
        return y >= INTERIOR_MIN_Y && y <= INTERIOR_MAX_Y && Math.abs(x - a.originX()) <= 1 && Math.abs(z - a.originZ()) <= 1;
    }

    public static Set<TicketPlanner.Chunk> chunkColumns(Allocation a) {
        Bounds b = shell(a);
        Set<TicketPlanner.Chunk> set = new TreeSet<>();
        for (int x = Math.floorDiv(b.minX(), 16); x <= Math.floorDiv(b.maxX(), 16); x++)
            for (int z = Math.floorDiv(b.minZ(), 16); z <= Math.floorDiv(b.maxZ(), 16); z++) set.add(new TicketPlanner.Chunk(x, z));
        return Collections.unmodifiableSet(set);
    }

    public static int shellBlockCount() { return 15 * 15 * 8 - WIDTH * WIDTH * CLEAR_HEIGHT; }


    public static boolean mayUse(Archive archive, UUID actor, boolean operator, ServerPolicy policy, HubPolicy hub) {
        return hub.enabled() && archive.basement().installed() && (operator || AccessControl.isOwner(archive, actor));
    }


    public enum Placement { OK, LIFT_COLUMN, SLOT_ONLY_PADS, PAD_ONLY_ON_SLOTS }

    public static Placement placement(Allocation a, int x, int y, int z, boolean padBlock) {
        int slot = slotAt(a.originX(), a.originZ(), x, y, z);
        if (padBlock) return slot >= 0 ? Placement.OK : Placement.PAD_ONLY_ON_SLOTS;
        if (!shell(a).contains(x, y, z)) return Placement.OK;
        if (inLiftColumn(a, x, y, z)) return Placement.LIFT_COLUMN;
        if (slot >= 0) return Placement.SLOT_ONLY_PADS;
        return Placement.OK;
    }


    public boolean installed() { return installed; }
    public Map<Integer, Destination> destinations() { return Collections.unmodifiableMap(destinations); }
    public Map<Integer, Integer> bindings() { return Collections.unmodifiableMap(bindings); }
    public int nextDestinationId() { return nextDestinationId; }
    public Optional<Destination> destination(int id) { return Optional.ofNullable(destinations.get(id)); }
    public Optional<Destination> destinationOfSlot(int slot) {
        Integer id = bindings.get(slot);
        return id == null ? Optional.empty() : Optional.ofNullable(destinations.get(id));
    }
    public int slotOf(int destinationId) {
        for (Map.Entry<Integer, Integer> e : bindings.entrySet()) if (e.getValue() == destinationId) return e.getKey();
        return -1;
    }

    void markInstalled() { installed = true; }

    private void requireInstalled() { if (!installed) throw new ArchiveException("error.nullspace.basement.not_installed"); }

    Destination add(String name, String dimension, double x, double y, double z, float yaw, String icon, int max) {
        requireInstalled();
        if (destinations.size() >= Math.min(max, MAX_DESTINATIONS)) throw new ArchiveException("error.nullspace.destination.limit", Math.min(max, MAX_DESTINATIONS));
        Destination created = Destination.create(nextDestinationId, name, dimension, x, y, z, yaw, icon);
        for (Destination other : destinations.values()) if (other.sameName(created.name())) throw new ArchiveException("error.nullspace.destination.duplicate", created.name());
        destinations.put(created.id(), created);
        nextDestinationId++;
        return created;
    }

    Destination rename(int id, String name) {
        requireInstalled();
        Destination current = require(id);
        Destination renamed = current.withName(name);
        for (Destination other : destinations.values()) if (other.id() != id && other.sameName(renamed.name())) throw new ArchiveException("error.nullspace.destination.duplicate", renamed.name());
        destinations.put(id, renamed);
        return renamed;
    }

    Destination setIcon(int id, String icon) {
        requireInstalled();
        Destination changed = require(id).withIcon(icon);
        destinations.put(id, changed);
        return changed;
    }

    Destination remove(int id) {
        requireInstalled();
        Destination removed = require(id);
        destinations.remove(id);
        bindings.values().removeIf(v -> v == id);
        return removed;
    }

    void bind(int slot, int destinationId) {
        requireInstalled();
        if (slot < 0 || slot >= SLOT_COUNT) throw new ArchiveException("error.nullspace.destination.bad_slot");
        require(destinationId);
        bindings.values().removeIf(v -> v == destinationId);
        bindings.put(slot, destinationId);
    }

    void unbind(int slot) {
        requireInstalled();
        bindings.remove(slot);
    }

    private Destination require(int id) {
        Destination d = destinations.get(id);
        if (d == null) throw new ArchiveException("error.nullspace.destination.unknown");
        return d;
    }

    int restore(boolean installed, int nextId, List<Destination> saved, Map<Integer, Integer> savedBindings) {
        int dropped = 0;
        this.installed = installed;
        destinations.clear(); bindings.clear();
        int maxId = 0;
        for (Destination d : saved) {
            if (destinations.size() >= MAX_DESTINATIONS || destinations.containsKey(d.id()) || destinations.values().stream().anyMatch(o -> o.sameName(d.name()))) { dropped++; continue; }
            destinations.put(d.id(), d);
            maxId = Math.max(maxId, d.id());
        }
        savedBindings.forEach((slot, id) -> {
            if (slot >= 0 && slot < SLOT_COUNT && destinations.containsKey(id) && !bindings.containsValue(id)) bindings.put(slot, id);
        });
        dropped += savedBindings.size() - bindings.size();
        nextDestinationId = Math.max(Math.max(1, nextId), maxId + 1);
        return dropped;
    }
}
