package dev.nullspace.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class Room {
    public static final int STRIDE = 24;
    public static final int CLEAR_HEIGHT = 6;
    public static final int FLOOR_Y = 64;
    public static final int MAX_WIDTH = 21;
    public static final int MAX_ROOM_WIDTH = 17;
    public static final int MAX_HEIGHT = 12;
    public static final int STARTER_ID = 0;
    private static final int[] WIDTHS = {9, 13, 17, 21};
    private static final int[] HEIGHTS = {6, 9, 12};
    public static final int MAX_U = MAX_WIDTH / 2 + 1, MAX_V = MAX_HEIGHT + 1;

    public record FinishCoordinate(Surface surface, int u, int v) {
        public FinishCoordinate { Objects.requireNonNull(surface); }
    }

    private final int id;
    private final int gridX;
    private final int gridZ;
    private int tier;
    private int heightTier;
    private final EnumMap<Direction, Integer> connections = new EnumMap<>(Direction.class);
    private final EnumMap<Surface, String> finishes = new EnumMap<>(Surface.class);
    private final Map<FinishCoordinate, String> individualFinishes = new HashMap<>();
    // what the owner calls the room; empty means unnamed and the room is known by its number
    public static final int MAX_NAME = 24;
    private String name = "";

    public Room(int id, int gridX, int gridZ, int tier) { this(id, gridX, gridZ, tier, 0); }
    public Room(int id, int gridX, int gridZ, int tier, int heightTier) {
        if (id < 0 || tier < 0 || tier >= WIDTHS.length || heightTier < 0 || heightTier >= HEIGHTS.length) throw ArchiveException.corrupt("room");
        if (tier > maxTier(id)) throw ArchiveException.corrupt("room-width");
        int margin = WIDTHS[maxTier(id)] / 2 + 2;
        if (Math.abs((long) gridX * STRIDE) + margin > Allocation.BUILD_RADIUS || Math.abs((long) gridZ * STRIDE) + margin > Allocation.BUILD_RADIUS)
            throw new ArchiveException("error.nullspace.room.space_taken");
        this.id = id; this.gridX = gridX; this.gridZ = gridZ; this.tier = tier; this.heightTier = heightTier;
        for (Surface surface : Surface.values()) finishes.put(surface, "nullspace:ivory");
        finishes.put(Surface.FLOOR, "nullspace:charcoal");
        finishes.put(Surface.CEILING, "nullspace:ivory");
    }
    public static Room restore(int id, int gridX, int gridZ, int tier, Map<Direction, Integer> connections,
                               Map<Surface, String> finishes, Map<FinishCoordinate, String> individualFinishes) {
        return restore(id, gridX, gridZ, tier, 0, connections, finishes, individualFinishes);
    }
    public static Room restore(int id, int gridX, int gridZ, int tier, int heightTier, Map<Direction, Integer> connections,
                               Map<Surface, String> finishes, Map<FinishCoordinate, String> individualFinishes) {
        Room room = new Room(id, gridX, gridZ, tier, heightTier);
        connections.forEach((direction, neighbor) -> {
            if (neighbor < 0 || neighbor == id) throw ArchiveException.corrupt("room-connection");
            room.connections.put(Objects.requireNonNull(direction), neighbor);
        });
        finishes.forEach(room::setSurfaceFinish);
        individualFinishes.forEach(room::setIndividualFinish);
        return room;
    }
    public int id() { return id; }
    public int gridX() { return gridX; }
    public int gridZ() { return gridZ; }
    public int tier() { return tier; }
    public int heightTier() { return heightTier; }
    public int width() { return WIDTHS[tier]; }
    public int height() { return HEIGHTS[heightTier]; }
    public static int maxTier(int roomId) { return roomId == STARTER_ID ? WIDTHS.length - 1 : WIDTHS.length - 2; }
    public static int nextWidth(int roomId, int width) {
        for (int t = 0; t < maxTier(roomId); t++) if (WIDTHS[t] == width) return WIDTHS[t + 1];
        return -1;
    }
    public static int nextHeight(int height) {
        for (int t = 0; t < HEIGHTS.length - 1; t++) if (HEIGHTS[t] == height) return HEIGHTS[t + 1];
        return -1;
    }
    public static int widthOfTier(int tier) { return WIDTHS[tier]; }
    public static int[] widths() { return WIDTHS.clone(); }
    public static int[] heights() { return HEIGHTS.clone(); }
    public static int heightOfTier(int heightTier) { return HEIGHTS[heightTier]; }
    public int maxTier() { return maxTier(id); }
    public int maxWidth() { return WIDTHS[maxTier()]; }
    public int nextWidth() { return tier >= maxTier() ? -1 : WIDTHS[tier + 1]; }
    public int nextHeight() { return heightTier >= HEIGHTS.length - 1 ? -1 : HEIGHTS[heightTier + 1]; }
    public int centerX(Allocation allocation) { return allocation.originX() + gridX * STRIDE; }
    public int centerZ(Allocation allocation) { return allocation.originZ() + gridZ * STRIDE; }
    public Map<Direction, Integer> connections() { return Collections.unmodifiableMap(connections); }
    public Map<Surface, String> finishes() { return Collections.unmodifiableMap(finishes); }
    public Map<FinishCoordinate, String> individualFinishes() { return Collections.unmodifiableMap(individualFinishes); }
    public Bounds interior(Allocation allocation) { return bounds(allocation, width(), height(), false); }
    public Bounds shell(Allocation allocation) { return bounds(allocation, width(), height(), true); }
    public Bounds reservedBounds(Allocation allocation) { return bounds(allocation, maxWidth(), MAX_HEIGHT, true); }
    public Bounds bounds(Allocation allocation, int width, boolean includeShell) { return bounds(allocation, width, height(), includeShell); }
    public Bounds bounds(Allocation allocation, int width, int height, boolean includeShell) {
        int radius = width / 2 + (includeShell ? 1 : 0);
        int cx = centerX(allocation), cz = centerZ(allocation);
        return new Bounds(cx - radius, FLOOR_Y + (includeShell ? 0 : 1), cz - radius,
            cx + radius, FLOOR_Y + height + (includeShell ? 1 : 0), cz + radius);
    }
    public String name() { return name; }
    public void setName(String name) { this.name = cleanName(name); }
    // control and format characters (newlines, section sign colour codes, zero width marks) go, runs of whitespace
    // become one space, and the rest is cut to MAX_NAME characters
    public static String cleanName(String raw) {
        if (raw == null) return "";
        String plain = raw.replaceAll("\u00a7.?", "");
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < plain.length(); ) {
            int c = plain.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) { space = out.length() > 0; continue; }
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.PRIVATE_USE
                || Character.getType(c) == Character.SURROGATE || Character.getType(c) == Character.UNASSIGNED) continue;
            if (space) { out.append(' '); space = false; }
            out.appendCodePoint(c);
        }
        String clean = out.toString();
        return clean.codePointCount(0, clean.length()) <= MAX_NAME ? clean : clean.substring(0, clean.offsetByCodePoints(0, MAX_NAME)).strip();
    }
    public String finish(FinishCoordinate coordinate) { return individualFinishes.getOrDefault(coordinate, finishes.get(coordinate.surface)); }
    public void setSurfaceFinish(Surface surface, String finish) {
        requireFinish(finish);
        finishes.put(Objects.requireNonNull(surface), finish);
        individualFinishes.keySet().removeIf(key -> key.surface == surface);
    }
    public void setIndividualFinish(FinishCoordinate coordinate, String finish) {
        Objects.requireNonNull(coordinate); requireFinish(finish);
        if (Math.abs(coordinate.u) > MAX_U || coordinate.v < -MAX_V || coordinate.v > MAX_V) {
            throw ArchiveException.corrupt("finish-coordinate");
        }
        individualFinishes.put(coordinate, finish);
    }
    private static void requireFinish(String finish) {
        if (finish == null || !finish.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new ArchiveException("error.nullspace.finish.invalid");
    }
    // doorway frame: 2 either side of the centre, up to the lintel row (the doorway itself is 3 wide, 3 high)
    public static final int DOORWAY_REACH = 2, DOORWAY_LINTEL = 4;
    // so a later repaint of the wall does not put the windows back over the hallway
    void clearDoorwayWindows(Surface wall) {
        individualFinishes.entrySet().removeIf(e -> e.getKey().surface == wall && Math.abs(e.getKey().u) <= DOORWAY_REACH
            && e.getKey().v >= 0 && e.getKey().v <= DOORWAY_LINTEL && "nullspace:window".equals(e.getValue()));
    }
    void enlarge() { if (tier >= maxTier()) throw new ArchiveException("error.nullspace.room.max_size"); tier++; }
    void raise() { if (heightTier >= HEIGHTS.length - 1) throw new ArchiveException("error.nullspace.room.max_height"); heightTier++; }
    void connect(Direction direction, int id) {
        if (connections.putIfAbsent(direction, id) != null) throw new ArchiveException("error.nullspace.room.doorway_used");
    }
    void disconnect(Direction direction) { connections.remove(direction); }
}
