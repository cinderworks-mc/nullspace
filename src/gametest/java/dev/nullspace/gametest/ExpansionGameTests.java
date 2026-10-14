package dev.nullspace.gametest;

import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.Bounds;
import dev.nullspace.core.LayoutPlanner;
import dev.nullspace.core.PendingEdit;
import dev.nullspace.core.Room;
import dev.nullspace.core.ServerPolicy;
import dev.nullspace.core.Surface;
import dev.nullspace.core.TicketPlanner;
import dev.nullspace.server.ArchiveService;
import dev.nullspace.server.EditJournal;
import dev.nullspace.server.SaveHooks;
import dev.nullspace.server.ServerConfig;
import dev.nullspace.server.StarterRooms;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

// room size limits and a save written by an older build
@PrefixGameTestTemplate(false)
public final class ExpansionGameTests {
    private static final String T = "empty";

    private static final LayoutPlanner.Axis WIDTH = LayoutPlanner.Axis.WIDTH, HEIGHT = LayoutPlanner.Axis.HEIGHT;

    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static ServerLevel level(GameTestHelper h) { return h.getLevel(); }

    private static Archive newArchive(GameTestHelper h, UUID owner) {
        Archive archive = ArchiveService.registryOf(h.getLevel().getServer()).claim(owner, StarterRooms.CLASSIC);
        StarterRooms.place(h.getLevel(), archive);
        archive.markInitialized();
        return archive;
    }

    private static void edit(GameTestHelper h, Archive a, int room, LayoutPlanner.Axis axis) {
        EditJournal.edit(h.getLevel().getServer(), level(h), a, room, axis, ServerPolicy.DEFAULT);
    }

    private static List<BlockState> snapshot(ServerLevel level, Bounds b) {
        var list = new ArrayList<BlockState>();
        for (int x = b.minX(); x <= b.maxX(); x++) for (int y = b.minY(); y <= b.maxY(); y++) for (int z = b.minZ(); z <= b.maxZ(); z++) list.add(level.getBlockState(new BlockPos(x, y, z)));
        return list;
    }

    private static Bounds area(Archive a, Room... rooms) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (Room r : rooms) { var b = r.reservedBounds(a.allocation()); x0 = Math.min(x0, b.minX()); z0 = Math.min(z0, b.minZ()); x1 = Math.max(x1, b.maxX()); z1 = Math.max(z1, b.maxZ()); }
        return new Bounds(x0 - 1, Room.FLOOR_Y - 1, z0 - 1, x1 + 1, Room.FLOOR_Y + Room.MAX_HEIGHT + 3, z1 + 1);
    }

    @GameTest(template = T, templateNamespace = "nullspace", timeoutTicks = 300)
    public static void configCapsOnlyBlockFutureUpgradesAndNeverShrinkARoom(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        Archive a = newArchive(h, owner);
        ServerLevel level = level(h);
        var widthCap = ServerConfig.MAX_ROOM_WIDTH;
        var heightCap = ServerConfig.MAX_CEILING_HEIGHT;
        int oldW = widthCap.get(), oldH = heightCap.get();
        try {
            expect(ArchiveService.currentPolicy().maxRoomWidth() == 21 && ArchiveService.currentPolicy().maxCeilingHeight() == 12, "defaults allow everything");
            for (int i = 0; i < 2; i++) edit(h, a, 0, HEIGHT);
            for (int i = 0; i < 2; i++) edit(h, a, 0, WIDTH);
            Room room = a.room(0);
            var before = snapshot(level, area(a, room));
            heightCap.set(6); widthCap.set(9);
            expect(ArchiveService.currentPolicy().maxCeilingHeight() == 6 && ArchiveService.currentPolicy().maxRoomWidth() == 9, "lowered caps are applied");
            expect(room.width() == 17 && room.height() == 12, "a lower cap never shrinks an existing room");
            expect(snapshot(level, area(a, room)).equals(before), "a lower cap never touches the world");
            boolean refused = false;
            try { EditJournal.edit(level.getServer(), level, a, 0, WIDTH, ArchiveService.currentPolicy()); } catch (IllegalArgumentException e) { refused = e instanceof ArchiveException ae && ae.key().equals("error.nullspace.room.width_vetoed"); }
            expect(refused, "the next widening is refused by its own message");
            expect(EditJournal.pending(level.getServer()).get(owner) == null, "a refused upgrade leaves no journal entry");
            widthCap.set(17);
            expect(ArchiveService.currentPolicy().maxRoomWidth() == 17, "17 switches the Hall off");
            refused = false;
            try { EditJournal.edit(level.getServer(), level, a, 0, WIDTH, ArchiveService.currentPolicy()); } catch (IllegalArgumentException e) { refused = true; }
            expect(refused, "the Hall is refused at cap 17");
            widthCap.set(21);
            EditJournal.edit(level.getServer(), level, a, 0, WIDTH, ArchiveService.currentPolicy());
            expect(room.width() == 21, "with the cap back at 21 the Hall is allowed");
        } finally { widthCap.set(oldW); heightCap.set(oldH); }
        h.succeed();
    }

    @GameTest(template = T, templateNamespace = "nullspace", timeoutTicks = 200)
    public static void anArchiveSavedByTheOldBuildLoadsUnchanged(GameTestHelper h) throws Exception {
        String snbt, expected;
        try (var in = ExpansionGameTests.class.getResourceAsStream("/nullspace_fixtures/old_build_archive.snbt")) { snbt = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        try (var in = ExpansionGameTests.class.getResourceAsStream("/nullspace_fixtures/old_build_archive.expected")) { expected = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        var tag = TagParser.parseTag(snbt);
        // an unfinished width edit, written the way the old build wrote journal entries (no height field)
        var entry = new CompoundTag();
        entry.putUUID("owner", UUID.fromString("00000000-0000-0000-0000-0000000000a1")); entry.putString("kind", "EXPAND"); entry.putInt("room", 2); entry.putInt("oldTier", 1);
        entry.putInt("direction", 0); entry.putInt("newRoom", 0); entry.putInt("gridX", 0); entry.putInt("gridZ", 0);
        var journal = new ListTag(); journal.add(entry);
        tag.put("pendingEdits", journal);
        var fixture = SaveHooks.loadFixture(tag);
        UUID owner = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        Archive a = fixture.registry().find(owner).orElseThrow(() -> new AssertionError("the old archive record was not loaded"));
        expect(a.rooms().size() == 4, "all four rooms load");
        for (String line : expected.split("\n")) {
            String[] t = line.split(" ");
            if (t[0].equals("room")) {
                Room r = a.room(Integer.parseInt(t[1]));
                expect(r.tier() == Integer.parseInt(t[2]) && r.width() == Integer.parseInt(t[3]), "room " + r.id() + " keeps its width");
                expect(r.heightTier() == 0 && r.height() == 6, "room " + r.id() + " is still six high");
                Bounds s = r.shell(a.allocation()), v = r.reservedBounds(a.allocation());
                int[] shell = {s.minX(), s.minY(), s.minZ(), s.maxX(), s.maxY(), s.maxZ()}, reserved = {v.minX(), v.minY(), v.minZ(), v.maxX(), v.maxY(), v.maxZ()};
                for (int i = 0; i < 6; i++) expect(shell[i] == Integer.parseInt(t[5 + i]), "room " + r.id() + " shell bound " + i + " is " + shell[i] + ", the old build had " + t[5 + i]);
                // the reservation only grows for the starter room (21 wide), and (upwards only) for the tallest ceiling; not sideways for anyone else
                if (r.id() != 0) for (int i = 0; i < 6; i++) if (i != 4) expect(reserved[i] == Integer.parseInt(t[12 + i]), "room " + r.id() + " reservation bound " + i + " unchanged");
                expect(reserved[4] == Room.FLOOR_Y + Room.MAX_HEIGHT + 1, "the reservation now covers the tallest ceiling");
            } else if (t[0].equals("chunks")) {
                int chunks = TicketPlanner.roomChunks(a).size();
                expect(chunks == Integer.parseInt(t[1]), "anchor chunk count unchanged: " + chunks + " vs " + t[1]);
            }
        }
        expect(a.room(0).finish(new Room.FinishCoordinate(Surface.SOUTH, 0, 3)).equals("nullspace:window"), "the starter window override loads");
        expect(a.room(0).finish(new Room.FinishCoordinate(Surface.EAST, -9, 9)).equals("nullspace:wood"), "the v=9 override loads");
        expect(a.room(0).finishes().get(Surface.NORTH).equals("nullspace:copper"), "surface finishes load");
        expect(a.anchorInstalled() && a.presets().containsKey("Builders"), "anchor and presets load");
        var pending = fixture.pendingEdits().get(owner);
        expect(pending != null && pending.kind() == PendingEdit.Kind.EXPAND && pending.roomId() == 2 && pending.oldTier() == 1 && pending.oldHeightTier() == 0, "an old journal entry reads as a width edit: " + pending);
        // saving it again only adds the new keys; everything the old build wrote is unchanged
        var resaved = fixture.resaved().copy();
        for (var archive : resaved.getList("archives", 10)) for (var room : ((CompoundTag) archive).getList("rooms", 10)) {
            var rc = (CompoundTag) room;
            expect(rc.getInt("heightTier") == 0, "a loaded old room is saved with height tier 0");
            rc.remove("heightTier");
        }
        for (var e : resaved.getList("pendingEdits", 10)) ((CompoundTag) e).remove("oldHeightTier");
        resaved.remove("format");
        var original = tag.copy();
        for (String key : new String[] {"pendingReturns", "keptDeaths"}) { resaved.remove(key); original.remove(key); }
        sortOverrides(resaved); sortOverrides(original);
        expect(resaved.equals(original), "re-saving an old record changes nothing but the new keys:\n" + resaved + "\n" + original);
        h.succeed();
    }

    // the per-block overrides and the permission sets are hash-ordered: their order in a save is not stable, their
    // content is
    private static void sortOverrides(CompoundTag tag) {
        for (var archive : tag.getList("archives", 10)) {
            for (var preset : ((CompoundTag) archive).getList("presets", 10)) {
                var pc = (CompoundTag) preset;
                var perms = new ArrayList<Tag>(pc.getList("permissions", 8));
                perms.sort(Comparator.comparing(Object::toString));
                var plist = new ListTag(); plist.addAll(perms);
                pc.put("permissions", plist);
            }
            for (var room : ((CompoundTag) archive).getList("rooms", 10)) {
            var rc = (CompoundTag) room;
            var sorted = new ArrayList<Tag>(rc.getList("individualFinishes", 10));
            sorted.sort(Comparator.comparing(Object::toString));
            var list = new ListTag(); list.addAll(sorted);
            rc.put("individualFinishes", list);
            }
        }
    }
}
