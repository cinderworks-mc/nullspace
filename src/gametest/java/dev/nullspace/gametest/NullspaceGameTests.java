package dev.nullspace.gametest;

import com.mojang.authlib.GameProfile;
import dev.nullspace.content.Content;
import dev.nullspace.content.ShellBlock;
import dev.nullspace.core.AccessControl;
import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveRegistry;
import dev.nullspace.core.Bounds;
import dev.nullspace.core.CardGrant;
import dev.nullspace.core.Invitation;
import dev.nullspace.core.LayoutPlanner;
import dev.nullspace.core.Permission;
import dev.nullspace.core.Progression;
import dev.nullspace.core.Recall;
import dev.nullspace.core.ReturnLocation;
import dev.nullspace.core.Room;
import dev.nullspace.core.ServerPolicy;
import dev.nullspace.core.Surface;
import dev.nullspace.network.TerminalAction;
import dev.nullspace.network.TerminalSnapshot;
import dev.nullspace.server.ArchiveService;
import dev.nullspace.server.AutomationGuard;
import dev.nullspace.server.Finishes;
import dev.nullspace.server.RoomBuilder;
import dev.nullspace.server.SaveHooks;
import dev.nullspace.server.StarterRooms;
import dev.nullspace.server.TerminalService;
import dev.nullspace.text.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

// archives, rooms, permissions, cards, the terminal protocol and the shell, in a real server
@PrefixGameTestTemplate(false)
public final class NullspaceGameTests {
    private static final String T = "empty";

    private static ServerLevel archiveLevel(GameTestHelper h) { return h.getLevel(); }

    private static ServerPlayer player(GameTestHelper h, String name) {
        return FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), name));
    }

    private static Archive newArchive(GameTestHelper h, UUID owner) {
        Archive archive = ArchiveService.registryOf(h.getLevel().getServer()).claim(owner, StarterRooms.CLASSIC);
        StarterRooms.place(archiveLevel(h), archive);
        archive.markInitialized();
        return archive;
    }

    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    @GameTest(template = T, templateNamespace = "nullspace")
    public static void starterRoomIsFurnishedAndSealed(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        Archive a = newArchive(h, owner);
        ServerLevel level = archiveLevel(h);
        Room room = a.room(0);
        int cx = room.centerX(a.allocation()), cz = room.centerZ(a.allocation());
        var interior = room.interior(a.allocation());
        expect(level.getBlockState(new BlockPos(cx, interior.minY(), interior.minZ())).is(Content.TERMINAL.get()), "terminal missing");
        expect(level.getBlockState(new BlockPos(cx - 1, interior.minY(), interior.minZ())).is(Content.EXIT.get()), "exit missing");
        expect(level.getBlockState(new BlockPos(cx + 1, interior.minY(), interior.minZ())).is(Blocks.CRAFTING_TABLE), "crafting table missing");
        expect(level.getBlockState(new BlockPos(cx + 2, interior.minY(), interior.minZ())).is(Blocks.FURNACE), "furnace missing");
        expect(level.getBlockState(new BlockPos(interior.maxX(), interior.minY(), cz - 1)).is(Blocks.BARREL), "barrel missing");
        expect(level.getBlockState(new BlockPos(interior.minX(), interior.minY(), cz)).is(Blocks.RED_BED), "bed head missing");
        expect(level.getBlockState(new BlockPos(cx, interior.minY(), cz)).isAir(), "centre must be clear");
        var shell = room.shell(a.allocation());
        expect(level.getBlockState(new BlockPos(cx, shell.minY(), cz)).is(Content.SHELL.get()), "floor missing");
        expect(level.getBlockState(new BlockPos(cx, shell.maxY(), cz)).is(Content.SHELL.get()), "ceiling missing");
        expect(level.getBlockState(new BlockPos(shell.minX(), shell.minY() + 2, cz)).is(Content.SHELL.get()), "west wall missing");
        h.succeed();
    }

    private static boolean entitiesLoaded(ServerLevel level, Bounds b) {
        for (int cx = Math.floorDiv(b.minX() - 1, 16); cx <= Math.floorDiv(b.maxX() + 1, 16); cx++)
            for (int cz = Math.floorDiv(b.minZ() - 1, 16); cz <= Math.floorDiv(b.maxZ() + 1, 16); cz++)
                if (!level.areEntitiesLoaded(ChunkPos.asLong(cx, cz))) return false;
        return true;
    }

    // runs the body on the first tick at which the area's entity storage is loaded; a failure inside it fails the
    // test instead of the server tick
    static void whenEntitiesLoaded(GameTestHelper h, ServerLevel level, Bounds area, Runnable body) {
        boolean[] done = {false};
        h.onEachTick(() -> {
            if (done[0] || !entitiesLoaded(level, area)) return;
            done[0] = true;
            try { body.run(); } catch (Throwable t) { h.fail(String.valueOf(t.getMessage())); }
        });
    }

    private static void expandTwiceAndCheck(Archive a, UUID owner, ServerLevel level, Room room, BlockPos barrel, BlockPos corner) {
        var interior = room.interior(a.allocation());
        int cz = room.centerZ(a.allocation());
        for (int step = 0; step < 2; step++) {
            var plan = LayoutPlanner.previewExpansion(a, owner, 0);
            RoomBuilder.applyExpansion(level, a, plan);
            LayoutPlanner.commitExpansion(a, owner, plan);
        }
        expect(room.width() == 17, "room should be 17 wide");
        expect(((BarrelBlockEntity) level.getBlockEntity(barrel)).getItem(0).getCount() == 7, "barrel contents lost");
        expect(level.getBlockState(corner).is(Blocks.GOLD_BLOCK), "decoration block lost");
        expect(level.getBlockState(new BlockPos(interior.maxX() + 1, interior.minY() + 1, cz)).isAir(), "old east wall must be opened");
        var shell = room.shell(a.allocation());
        expect(level.getBlockState(new BlockPos(shell.maxX(), shell.minY() + 2, cz)).is(Content.SHELL.get()), "new east wall missing");
        expect(level.getBlockState(new BlockPos(room.centerX(a.allocation()), shell.maxY(), cz)).is(Content.SHELL.get()), "new ceiling missing");
        expect(level.getBlockState(new BlockPos(room.centerX(a.allocation()), shell.maxY() - 1, cz)).isAir(), "new interior must be clear");
    }

    private static ArmorStand spawnStand(ServerLevel level, BlockPos corner) {
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        stand.setPos(corner.getX() - 1.5, corner.getY(), corner.getZ() - 1.5);
        level.addFreshEntity(stand);
        return stand;
    }

    @GameTest(template = T, templateNamespace = "nullspace", timeoutTicks = 600)
    public static void expansionPreservesBlocksInventoriesAndEntities(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        Archive a = newArchive(h, owner);
        ServerLevel level = archiveLevel(h);
        Room room = a.room(0);
        var interior = room.interior(a.allocation());
        int cz = room.centerZ(a.allocation());
        BlockPos barrel = new BlockPos(interior.maxX(), interior.minY(), cz - 1);
        ((BarrelBlockEntity) level.getBlockEntity(barrel)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        BlockPos corner = new BlockPos(interior.maxX(), interior.minY(), interior.maxZ());
        level.setBlock(corner, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        var area = room.bounds(a.allocation(), 17, true);
        RoomBuilder.prewarm(level, area);
        whenEntitiesLoaded(h, level, area, () -> {
            ArmorStand stand = spawnStand(level, corner);
            expect(level.getEntity(stand.getUUID()) != null, "entity must be visible when added to a loaded chunk");
            expandTwiceAndCheck(a, owner, level, room, barrel, corner);
            expect(stand.isAlive() && level.getEntity(stand.getUUID()) != null, "entity lost");
            h.succeed();
        });
    }

    @GameTest(template = T, templateNamespace = "nullspace")
    public static void permissionsAreEnforcedPerArchiveAndPlayer(GameTestHelper h) {
        var owner = player(h, "owner");
        var guest = player(h, "guest");
        var stranger = player(h, "stranger");
        Archive a = newArchive(h, owner.getUUID());
        BlockPos inside = new BlockPos(a.originX(), Room.FLOOR_Y + 1, a.originZ());
        var policy = ServerPolicy.DEFAULT;
        AccessControl.invite(a, owner.getUUID(), guest.getUUID(), Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, policy);
        AccessControl.enter(a, guest.getUUID(), new ReturnLocation("minecraft:overworld", 0, 70, 0, 0, 0), 0, policy);
        expect(ArchiveService.mayDo(owner, inside, Permission.BUILD, Permission.STORAGE), "owner must be allowed");
        expect(!ArchiveService.mayDo(guest, inside, Permission.BUILD), "default guest may not build");
        expect(!ArchiveService.mayDo(guest, inside, Permission.STORAGE), "default guest may not open storage");
        expect(!ArchiveService.mayDo(stranger, inside, Permission.ENTER), "stranger denied");
        AccessControl.setPermissions(a, owner.getUUID(), guest.getUUID(), Set.of(Permission.ENTER, Permission.STORAGE), policy);
        expect(ArchiveService.mayDo(guest, inside, Permission.STORAGE), "granted storage");
        expect(!ArchiveService.mayDo(guest, inside, Permission.STORAGE, Permission.BUILD), "build still denied");
        AccessControl.remove(a, owner.getUUID(), guest.getUUID());
        expect(!ArchiveService.mayDo(guest, inside, Permission.STORAGE), "removal revokes live rights");
        h.succeed();
    }

    // archive Cards: records, bindings, counters and a card visit survive save and reload; a damaged card does not cost
    // the archive; an old save has none
    @GameTest(template = T, templateNamespace = "nullspace")
    public static void archiveCardsSurviveSaveAndReloadAndDamagedOnesAreDropped(GameTestHelper h) {
        var registry = new ArchiveRegistry();
        UUID ownerId = UUID.randomUUID(), guestId = UUID.randomUUID();
        Archive a = registry.claim(ownerId, StarterRooms.CLASSIC); a.markInitialized();
        var policy = ServerPolicy.DEFAULT;
        var one = AccessControl.printCard(a, ownerId, UUID.randomUUID(), CardGrant.Kind.SINGLE_VISIT, 0, Set.of(Permission.ENTER), false, 1000L, policy);
        var two = AccessControl.printCard(a, ownerId, UUID.randomUUID(), CardGrant.Kind.TIMED, 3_600_000L, Set.of(Permission.ENTER, Permission.BUILD), true, 1000L, policy);
        var three = AccessControl.printCard(a, ownerId, UUID.randomUUID(), CardGrant.Kind.UNTIL_REVOKED, 0, Set.of(Permission.ENTER), false, 1000L, policy);
        AccessControl.enter(a, guestId, "Guest", new ReturnLocation("minecraft:overworld", 0, 70, 0, 0, 0), 2000L, policy, three.token());
        AccessControl.revokeCard(a, ownerId, one.number(), 3000L);
        var saved = SaveHooks.save(registry);
        var back = SaveHooks.load(saved).registry().find(ownerId).orElseThrow();
        expect(back.cards().equals(a.cards()) && back.nextCardNumber() == a.nextCardNumber(), "records, bindings, counters and the revoked flag round-trip: " + back.cards().values());
        expect(three.token().equals(back.activeVisits().get(guestId).cardToken()), "the card visit remembers its card");
        expect(SaveHooks.save(SaveHooks.load(saved).registry()).equals(saved), "save, load, save is stable");
        expect(saved.getInt("format") == 1, "the saved tag carries its format");
        var noFormat = saved.copy(); noFormat.remove("format");
        expect(SaveHooks.load(noFormat).registry().find(ownerId).isPresent(), "a save without a format key still loads");
        var plain = new ArchiveRegistry();
        plain.claim(UUID.randomUUID(), StarterRooms.CLASSIC).markInitialized();
        var plainTag = SaveHooks.save(plain);
        expect(!plainTag.getList("archives", Tag.TAG_COMPOUND).getCompound(0).contains("cards"), "an archive without cards writes no cards key, so an old save and a new one without cards are identical");
        var oldSave = SaveHooks.load(plainTag).registry().archives().values().iterator().next();
        expect(oldSave.cards().isEmpty() && oldSave.nextCardNumber() == 1, "a save from before cards loads with none");
        // damage: a card that is not a compound, one with ADMIN, and a duplicate token: only those are dropped, the archive and the other cards stay
        var damaged = saved.copy();
        var cardList = damaged.getList("archives", Tag.TAG_COMPOUND).getCompound(0).getCompound("cards").getList("list", Tag.TAG_COMPOUND);
        var admin = (CompoundTag) cardList.get(1).copy();
        var perms = new ListTag(); perms.add(StringTag.valueOf("ADMIN")); perms.add(StringTag.valueOf("ENTER"));
        admin.put("permissions", perms); admin.putUUID("token", UUID.randomUUID()); admin.putInt("number", 40);
        cardList.add(admin);
        cardList.add(new CompoundTag());
        cardList.add(cardList.get(0).copy());
        var r1 = SaveHooks.load(damaged).registry().find(ownerId);
        expect(r1.isPresent() && r1.get().cards().size() == 3 && r1.get().rooms().size() == 1, "damaged and duplicate entries are dropped, the archive and its three good cards stay: " + r1.map(x -> x.cards().size()).orElse(-1));
        expect(r1.get().nextCardNumber() >= 4, "numbers keep moving past the dropped entry");
        var wholeBad = saved.copy();
        wholeBad.getList("archives", Tag.TAG_COMPOUND).getCompound(0).put("cards", StringTag.valueOf("garbage"));
        var r2 = SaveHooks.load(wholeBad).registry().find(ownerId);
        expect(r2.isPresent() && r2.get().cards().isEmpty() && r2.get().activeVisits().containsKey(guestId), "a cards entry that is not a compound means no cards, never an unreadable archive");
        h.succeed();
    }

    @GameTest(template = T, templateNamespace = "nullspace")
    public static void pistonRuleProtectsShellAndBlockEntitiesOnly(GameTestHelper h) {
        // the piston listener itself only runs in the archive dimension (seen in a real run); this pins the rule it applies to the pushed/destroyed set
        ServerLevel level = h.getLevel();
        BlockPos shell = h.absolutePos(new BlockPos(1, 2, 1)), chest = h.absolutePos(new BlockPos(2, 2, 1)), stone = h.absolutePos(new BlockPos(3, 2, 1));
        level.setBlock(shell, Content.SHELL.get().defaultBlockState(), 3);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
        expect(AutomationGuard.pistonMovesProtected(level, List.of(shell)), "a shell block is never pushed");
        expect(AutomationGuard.pistonMovesProtected(level, List.of(stone, chest)), "a set containing a block entity is refused as a whole");
        expect(!AutomationGuard.pistonMovesProtected(level, List.of(stone)), "a plain block may move");
        expect(!AutomationGuard.pistonMovesProtected(level, List.of()), "nothing to move");
        h.succeed();
    }

    @GameTest(template = T, templateNamespace = "nullspace")
    public static void wholeSurfaceRepaintIsCosmeticAndStaysProtected(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        Archive a = newArchive(h, owner);
        ServerLevel level = archiveLevel(h);
        Room room = a.room(0);
        room.setSurfaceFinish(Surface.NORTH, "nullspace:green");
        int changed = RoomBuilder.repaintSurface(level, a, room, Surface.NORTH);
        expect(changed > 20, "north wall should be repainted");
        var shell = room.shell(a.allocation());
        int cx = room.centerX(a.allocation());
        var state = level.getBlockState(new BlockPos(cx + 3, Room.FLOOR_Y + 4, shell.minZ()));
        expect(state.is(Content.SHELL.get()) && state.getValue(ShellBlock.FINISH) == ShellBlock.Finish.GREEN, "wall not green");
        expect(state.getDestroySpeed(level, BlockPos.ZERO) < 0, "painted shell must stay unbreakable");
        expect(level.getBlockState(new BlockPos(cx, Room.FLOOR_Y + 1, shell.minZ() + 1)).is(Content.TERMINAL.get()), "terminal must be untouched");
        expect(!Finishes.supported("minecraft:diamond_block"), "only Nullspace finishes are supported");
        h.succeed();
    }

    @GameTest(template = T, templateNamespace = "nullspace")
    public static void terminalPacketsRoundTripAndMasksNeverCarryAdmin(GameTestHelper h) {
        var guest = new TerminalSnapshot.GuestInfo(UUID.randomUUID(), "Bartholomew_Pike", 1, 0b1011, true, 12_345L, false);
        Function<TerminalSnapshot.TeamInfo, TerminalSnapshot> build = team -> new TerminalSnapshot("Owner", List.of(new TerminalSnapshot.RoomInfo(3, 13, 9, 0b0101, List.of("nullspace:ivory", "nullspace:copper"))),
            List.of(guest), List.of(new TerminalSnapshot.PresetInfo("Co-op builders", 11)), 5, 4, true, false, 9, Msg.no("error.nullspace.cost.short", Msg.cost(new Progression.Cost(2, 4, 1), Content.EXPANDER.get())),
            Component.translatable("message.nullspace.preview.expand", 3, 13, 17, "cost"), true, 8, 0b101111, true, true, false, 45, 17, 9,
            true, true, 12, 250, true, List.of(new TerminalSnapshot.DestInfo(7, "Nether Hub", "minecraft:the_nether", -40, 64, 1200, "minecraft:netherrack", 3), new TerminalSnapshot.DestInfo(9, "Home", "minecraft:overworld", 5, 70, 5, "", -1)),
            team,
            List.of(new TerminalSnapshot.CardInfo(4, 1, 0, 3_600_000L, 0b1011, false, "Bartholomew_Pike", 2), new TerminalSnapshot.CardInfo(5, 2, 3, 0, 1, true, "", 0)),
            new TerminalSnapshot.CardsInfo(true, 8, 1, 3, 720));
        var snapshot = build.apply(new TerminalSnapshot.TeamInfo(4, true, 0b1001, 0b1001, "Night Shift", 5, 16, 2));
        var longTeam = build.apply(new TerminalSnapshot.TeamInfo(4, true, 0b1001, 0b1001, "L".repeat(150), 5, 16, 2));
        var longBuf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        TerminalSnapshot.CODEC.encode(longBuf, longTeam);
        expect(TerminalSnapshot.CODEC.decode(longBuf).team().name().equals("L".repeat(128)), "a 150 character team name must not break the snapshot");
        var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        TerminalSnapshot.CODEC.encode(buf, snapshot);
        var decoded = TerminalSnapshot.CODEC.decode(buf);
        expect(snapshot.equals(decoded), "snapshot must survive the codec");
        expect(decoded.cards().size() == 2 && decoded.cards().get(0).boundName().equals("Bartholomew_Pike") && decoded.cards().get(1).state() == 3 && decoded.cards().get(1).holderOnly() && decoded.cardSettings().maxLive() == 8 && decoded.cardSettings().blank() == 3,
            "the Archive Cards travel in the snapshot");
        expect(decoded.team().present() && decoded.team().status() == 4 && decoded.team().on() && decoded.team().name().equals("Night Shift") && decoded.team().size() == 5 && decoded.team().mode() == 2, "the team row travels in the snapshot");
        expect(decoded.recallInstalled() && !decoded.recallAllowed() && decoded.recallMinutes() == 45, "the Recall Module status travels in the snapshot");
        expect(decoded.hallInstalled() && decoded.maxDestinations() == 12 && decoded.chrononCost() == 250 && decoded.hasEntry() && decoded.destinations().size() == 2 && decoded.destinations().get(0).slot() == 3 && decoded.destinations().get(1).slot() == -1,
            "the Waypoint Hall status and the saved destinations travel in the snapshot");
        expect(decoded.rooms().get(0).height() == 9 && decoded.maxRoomWidth() == 17 && decoded.maxCeilingHeight() == 9, "room heights and the server caps travel in the snapshot");
        var install = new TerminalAction(TerminalAction.Kind.INSTALL_RECALL, "", 0, 0, 0);
        var buf3 = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        TerminalAction.CODEC.encode(buf3, install);
        expect(install.equals(TerminalAction.CODEC.decode(buf3)), "the install action survives the codec");
        expect(decoded.message().getContents() instanceof TranslatableContents tc && tc.getKey().equals("error.nullspace.cost.short"),
            "the status message must travel as a translation key, not as English text");
        var action = new TerminalAction(TerminalAction.Kind.INVITE, "Guest", 2, 1 | (3 << 8), 24);
        var buf2 = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        TerminalAction.CODEC.encode(buf2, action);
        expect(action.equals(TerminalAction.CODEC.decode(buf2)), "action must survive the codec");
        expect(!TerminalService.mask(-1).contains(Permission.ADMIN), "a forged mask must never grant ADMIN");
        expect(TerminalService.mask(0).equals(Set.of(Permission.ENTER)), "an empty mask still means enter-only");
        h.succeed();
    }

    private static CompoundTag savedArchive(CompoundTag world, UUID owner) {
        for (var t : world.getList("archives", 10)) if (((CompoundTag) t).getUUID("owner").equals(owner)) return (CompoundTag) t;
        throw new AssertionError("no archive for " + owner);
    }

    private static CompoundTag saveTwo(UUID a, UUID b) {
        var registry = new ArchiveRegistry();
        registry.claim(a, StarterRooms.CLASSIC).markInitialized();
        registry.claim(b, StarterRooms.CLASSIC).markInitialized();
        return SaveHooks.save(registry);
    }

    private static CompoundTag permanentInvite(UUID guest, String kind, String... permissions) {
        var invite = new CompoundTag();
        invite.putUUID("guest", guest); invite.putString("kind", kind); invite.putLong("expiresAtMillis", 0); invite.putBoolean("consumed", false);
        var list = new ListTag();
        for (String p : permissions) list.add(StringTag.valueOf(p));
        invite.put("permissions", list);
        return invite;
    }

    @GameTest(template = T, templateNamespace = "nullspace")
    public static void oneDamagedArchiveRecordNeverWipesTheOthersOrRecyclesItsAllocation(GameTestHelper h) {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        var intact = SaveHooks.load(saveTwo(a, b));
        expect(intact.registry().archives().size() == 2 && intact.registry().nextAllocationId() == 2 && intact.savedArchives() == 2, "undamaged data round-trips");
        var world = saveTwo(a, b);
        var broken = new ListTag();
        broken.add(permanentInvite(c, "NO_SUCH_KIND"));
        savedArchive(world, b).put("invitations", broken);
        var loaded = SaveHooks.load(world);
        var owners = new HashSet<UUID>(loaded.registry().archives().keySet());
        expect(owners.contains(a) && !owners.contains(b), "intact archive loads, unreadable one does not: " + owners);
        expect(loaded.registry().nextAllocationId() >= 2, "the unreadable archive's allocation must not be handed out again: " + loaded.registry().nextAllocationId());
        expect(loaded.savedArchives() == 2, "the unreadable record must be written back untouched for repair, was " + loaded.savedArchives());
        // ADMIN inside a guest invitation, duplicate owner, allocation beyond the world, and hostile pending entries are all survivable.
        world = saveTwo(a, b);
        var admin = new ListTag(); admin.add(permanentInvite(c, "PERMANENT", "ENTER", "ADMIN"));
        savedArchive(world, a).put("invitations", admin);
        owners = new HashSet<>(SaveHooks.load(world).registry().archives().keySet());
        expect(owners.equals(Set.of(b)), "an ADMIN guest invitation quarantines that archive only: " + owners);
        world = saveTwo(a, b);
        world.getList("archives", 10).add(savedArchive(world, a).copy());
        var dup = SaveHooks.load(world);
        expect(dup.registry().archives().size() == 2 && dup.savedArchives() == 3, "a duplicate owner is quarantined, not fatal");
        world = saveTwo(a, b);
        savedArchive(world, b).putLong("allocationId", Long.MAX_VALUE);
        var far = SaveHooks.load(world);
        expect(far.registry().find(a).isPresent() && far.registry().find(b).isEmpty() && far.registry().nextAllocationId() >= 2, "an impossible allocation id is quarantined");
        world = saveTwo(a, b);
        var edit = new CompoundTag(); edit.putUUID("owner", a); edit.putString("kind", "NO_SUCH_EDIT");
        var edits = new ListTag(); edits.add(edit); world.put("pendingEdits", edits);
        var pending = new CompoundTag(); pending.putUUID("player", c);
        var where = new CompoundTag(); where.putString("dimension", "Not A Dimension!"); where.putDouble("x", Double.NaN); pending.put("location", where);
        var returns = new ListTag(); returns.add(pending); world.put("pendingReturns", returns);
        var hostile = SaveHooks.load(world);
        expect(hostile.registry().archives().size() == 2 && hostile.pendingEdits() == 0 && hostile.pendingReturns() == 0, "hostile journal/return entries are skipped");
        h.succeed();
    }
}
