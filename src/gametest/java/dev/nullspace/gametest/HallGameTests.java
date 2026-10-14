package dev.nullspace.gametest;

import com.mojang.authlib.GameProfile;
import dev.nullspace.content.Content;
import dev.nullspace.content.ControlBlock;
import dev.nullspace.content.LiftPadBlock;
import dev.nullspace.content.ShellBlock;
import dev.nullspace.core.AccessControl;
import dev.nullspace.core.Archive;
import dev.nullspace.core.Basement;
import dev.nullspace.core.BlockPoint;
import dev.nullspace.core.Bounds;
import dev.nullspace.core.Invitation;
import dev.nullspace.core.Permission;
import dev.nullspace.core.Room;
import dev.nullspace.core.ServerPolicy;
import dev.nullspace.server.ArchiveService;
import dev.nullspace.server.AutomationGuard;
import dev.nullspace.server.EditJournal;
import dev.nullspace.server.ExitUpgrade;
import dev.nullspace.server.HubLift;
import dev.nullspace.server.StarterRooms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// the Waypoint Hall layout and lift, and the two block exit
@PrefixGameTestTemplate(false)
public final class HallGameTests {
    private static final String T = "empty";

    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static Archive newArchive(GameTestHelper h, UUID owner) {
        Archive archive = ArchiveService.registryOf(h.getLevel().getServer()).claim(owner, StarterRooms.CLASSIC);
        StarterRooms.place(h.getLevel(), archive);
        archive.markInitialized();
        return archive;
    }

    private static ServerPlayer player(GameTestHelper h, UUID id, String name) {
        return FakePlayerFactory.get(h.getLevel(), new GameProfile(id, name));
    }

    private static BlockPos pos(BlockPoint p) { return new BlockPos(p.x(), p.y(), p.z()); }

    private static Bounds area(Archive a) {
        int cx = Basement.centerX(a.allocation()), cz = Basement.centerZ(a.allocation());
        return new Bounds(cx - 13, Basement.FLOOR_Y - 1, cz - 13, cx + 13, Room.FLOOR_Y + Room.MAX_HEIGHT + 2, cz + 13);
    }

    private static List<BlockState> snapshot(ServerLevel level, Bounds b) {
        var list = new ArrayList<BlockState>();
        for (int x = b.minX(); x <= b.maxX(); x++) for (int y = b.minY(); y <= b.maxY(); y++) for (int z = b.minZ(); z <= b.maxZ(); z++) list.add(level.getBlockState(new BlockPos(x, y, z)));
        return list;
    }

    private static void install(GameTestHelper h, Archive a) { EditJournal.installHall(h.getLevel().getServer(), h.getLevel(), a); }

    @GameTest(template = T, templateNamespace = "nullspace", timeoutTicks = 200)
    public static void installBuildsTheHallAsLaidOut(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        Archive a = newArchive(h, owner);
        ServerLevel level = h.getLevel();
        var alloc = a.allocation();
        int cx = Basement.centerX(alloc), cz = Basement.centerZ(alloc);
        var starterBefore = snapshot(level, new Bounds(cx - 12, Room.FLOOR_Y, cz - 12, cx + 12, Room.FLOOR_Y + 13, cz + 12));
        var hallBefore = snapshot(level, Basement.shell(alloc));
        expect(hallBefore.stream().allMatch(BlockState::isAir), "the hall's volume is void before the install");
        install(h, a);
        expect(a.basement().installed(), "the domain records the hall");
        expect(EditJournal.pending(level.getServer()).get(owner) == null, "journal cleared");
        // starter room: one block changed (the centre of the floor)
        var starterAfter = snapshot(level, new Bounds(cx - 12, Room.FLOOR_Y, cz - 12, cx + 12, Room.FLOOR_Y + 13, cz + 12));
        int changed = 0; for (int i = 0; i < starterBefore.size(); i++) if (!starterBefore.get(i).equals(starterAfter.get(i))) changed++;
        expect(changed == 1, "the install changes exactly one block of the starter room, changed " + changed);
        BlockState up = level.getBlockState(pos(Basement.upperPad(alloc)));
        expect(up.is(Content.LIFT_PAD.get()) && up.getValue(LiftPadBlock.WAY) == LiftPadBlock.Way.DOWN, "upper pad at the starter centre " + up);
        BlockState down = level.getBlockState(pos(Basement.lowerPad(alloc)));
        expect(down.is(Content.LIFT_PAD.get()) && down.getValue(LiftPadBlock.WAY) == LiftPadBlock.Way.UP, "lower pad at the hall centre " + down);
        // every cell of the shell box
        Bounds shell = Basement.shell(alloc), in = Basement.interior(alloc);
        int dais = 0, bases = 0, shells = 0;
        for (int x = shell.minX(); x <= shell.maxX(); x++) for (int y = shell.minY(); y <= shell.maxY(); y++) for (int z = shell.minZ(); z <= shell.maxZ(); z++) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (in.contains(x, y, z)) continue;
            int u = x - cx, w = z - cz;
            switch (Basement.classify(u, y, w)) {
                case DAIS -> { expect(s.is(Content.WAYPOINT_DAIS.get()), "dais at " + u + "," + w + " " + s); dais++; }
                case SLOT -> { expect(s.is(Content.WAYPOINT_PEDESTAL.get()), "pedestal base at " + u + "," + w + " " + s); bases++; }
                default -> {
                    if (u == 0 && w == 0 && y == Basement.FLOOR_Y) break;
                    expect(s.getBlock() instanceof ShellBlock, "shell at " + u + "," + y + "," + w + " " + s);
                    ShellBlock.Finish want = y == Basement.CEILING_Y ? (Math.abs(u) <= 1 && Math.abs(w) <= 1 ? ShellBlock.Finish.AMBER : ShellBlock.Finish.CHARCOAL) : y == Basement.FLOOR_Y ? ShellBlock.Finish.CHARCOAL : ShellBlock.Finish.IVORY;
                    expect(s.getValue(ShellBlock.FINISH) == want, "finish at " + u + "," + y + "," + w + " is " + s.getValue(ShellBlock.FINISH) + ", wanted " + want);
                    shells++;
                }
            }
        }
        expect(dais == 8 && bases == 12, "8 dais plates and 12 pedestal bases, got " + dais + " and " + bases);
        expect(shells == Basement.shellBlockCount() - dais - bases - 1, "786 shell blocks in all: " + shells);
        for (BlockPoint p : Basement.pillars(alloc)) expect(level.getBlockState(pos(p)).is(Content.LAMP_PILLAR.get()), "pillar " + p);
        BlockState terminal = level.getBlockState(pos(Basement.terminal(alloc))), exit = level.getBlockState(pos(Basement.exit(alloc)));
        expect(terminal.is(Content.TERMINAL.get()) && terminal.getValue(ControlBlock.FACING) == Direction.SOUTH, "the hall has a terminal facing south");
        expect(exit.is(Content.EXIT.get()) && exit.getValue(ControlBlock.FACING) == Direction.SOUTH && exit.getValue(ControlBlock.HALF) == DoubleBlockHalf.LOWER, "and an exit, so nobody is trapped");
        BlockState exitUpper = level.getBlockState(pos(Basement.exitUpper(alloc)));
        expect(exitUpper.is(Content.EXIT.get()) && exitUpper.getValue(ControlBlock.FACING) == Direction.SOUTH && exitUpper.getValue(ControlBlock.HALF) == DoubleBlockHalf.UPPER, "the exit is a two block door");
        // the interior is otherwise empty (pads are placed by the owner, so the slots are free)
        int solid = 0;
        for (int x = in.minX(); x <= in.maxX(); x++) for (int y = in.minY(); y <= in.maxY(); y++) for (int z = in.minZ(); z <= in.maxZ(); z++)
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) solid++;
        expect(solid == 24 + 3, "only the four pillars (24 blocks), the terminal and the two halves of the exit stand inside, found " + solid);
        for (BlockPoint s : Basement.slots(alloc)) expect(level.getBlockState(pos(s)).isAir(), "the slots start free for the owner's pads");
        // every hall block counts as structural
        for (BlockPoint p : List.of(Basement.upperPad(alloc), Basement.lowerPad(alloc), Basement.slot(alloc, 0), Basement.pillars(alloc).get(0)))
            expect(AutomationGuard.structural(level.getBlockState(pos(p))) || level.getBlockState(pos(p)).isAir(), "structural " + p);
        expect(AutomationGuard.structural(level.getBlockState(pos(Basement.upperPad(alloc)))) && AutomationGuard.structural(level.getBlockState(pos(Basement.lowerPad(alloc)))), "pads are protected like the shell");
        expect(AutomationGuard.structural(Content.WAYPOINT_DAIS.get().defaultBlockState()) && AutomationGuard.structural(Content.WAYPOINT_PEDESTAL.get().defaultBlockState())
            && AutomationGuard.structural(Content.LAMP_PILLAR.get().defaultBlockState()), "dais, pedestal bases and pillars are protected too");
        expect(!AutomationGuard.structural(Content.DESTINATION_PAD.get().defaultBlockState()), "a Destination Pad is the owner's to take up and set down");
        // idempotent
        var after = snapshot(level, area(a));
        install(h, a);
        expect(snapshot(level, area(a)).equals(after), "a repeated install changes nothing");
        h.succeed();
    }

    private static void inviteAll(Archive a, UUID guest) {
        AccessControl.invite(a, a.owner(), guest, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER, Permission.STORAGE, Permission.INTERACT_BLOCK, Permission.BUILD,
            Permission.INTERACT_ENTITY, Permission.SET_SPAWN), 1_000L, ServerPolicy.DEFAULT);
    }

    @GameTest(template = T, templateNamespace = "nullspace", timeoutTicks = 400)
    public static void aDestinationPadCanOnlyBeBrokenByItsOwnerOrAnOperator(GameTestHelper h) {
        UUID ownerId = UUID.randomUUID(), guestId = UUID.randomUUID();
        Archive a = newArchive(h, ownerId);
        install(h, a);
        inviteAll(a, guestId);
        ServerPlayer owner = player(h, ownerId, "PadBreakOwner"), guest = player(h, guestId, "PadBreakGuest");
        var low = Basement.lowerLanding(a.allocation());
        var at = new BlockPos(low.x() + 4, low.y(), low.z());
        var pad = Content.DESTINATION_PAD.get().defaultBlockState();
        expect(!AutomationGuard.padBreakDenied(owner, at, pad), "the owner may break a pad");
        expect(AutomationGuard.padBreakDenied(guest, at, pad), "a guest with every delegable right may not break a pad");
        expect(!AutomationGuard.padBreakDenied(guest, at, Blocks.STONE.defaultBlockState()), "other blocks are unaffected by the pad rule");
        h.succeed();
    }

    @GameTest(template = T, templateNamespace = "nullspace", timeoutTicks = 400)
    public static void theLiftCarriesOnlyTheOwnerAndLandsOnTheExactCells(GameTestHelper h) {
        UUID ownerId = UUID.randomUUID(), guestId = UUID.randomUUID();
        Archive a = newArchive(h, ownerId);
        ServerLevel level = h.getLevel();
        var alloc = a.allocation();
        install(h, a);
        inviteAll(a, guestId);
        ServerPlayer owner = player(h, ownerId, "LiftOwner"), guest = player(h, guestId, "LiftGuest");
        var top = Basement.upperLanding(alloc);
        owner.setPos(top.x() + 0.5, top.y(), top.z() + 0.5);
        guest.setPos(top.x() + 0.5, top.y(), top.z() + 0.5);
        // (a FakePlayer's connection ignores teleports, so the checks use the landing the lift returns)
        expect(HubLift.ride(guest, true) == null, "a guest with every delegable right is not carried");
        var landing = HubLift.ride(owner, true);
        var low = Basement.lowerLanding(alloc);
        expect(landing != null && landing.distanceTo(new Vec3(low.x() + 0.5, low.y(), low.z() + 0.5)) < 0.01, "the owner lands on the hall's centre pad " + landing);
        h.runAfterDelay(45, () -> {
            expect(HubLift.ride(guest, false) == null, "a guest cannot ride up either");
            var back = HubLift.ride(owner, false);
            expect(back != null && back.distanceTo(new Vec3(top.x() + 0.5, top.y(), top.z() + 0.5)) < 0.01, "the owner lands back on the starter room's centre " + back);
            h.succeed();
        });
    }

    private static BlockPos starterExit(Archive a) {
        Room room = a.room(0);
        var in = room.interior(a.allocation());
        return new BlockPos(room.centerX(a.allocation()) - 1, in.minY(), in.minZ());
    }

    private static void expectWholeDoor(ServerLevel level, BlockPos lowerPos, String where) {
        BlockState lower = level.getBlockState(lowerPos), upper = level.getBlockState(lowerPos.above());
        expect(lower.is(Content.EXIT.get()) && lower.getValue(ControlBlock.HALF) == DoubleBlockHalf.LOWER, where + ": lower half " + lower);
        expect(upper.is(Content.EXIT.get()) && upper.getValue(ControlBlock.HALF) == DoubleBlockHalf.UPPER, where + ": upper half " + upper);
        expect(lower.getValue(ControlBlock.FACING) == Direction.SOUTH && upper.getValue(ControlBlock.FACING) == Direction.SOUTH, where + ": both halves face south");
        expect(level.getBlockState(lowerPos.above(2)).isAir(), where + ": headroom above the door " + level.getBlockState(lowerPos.above(2)));
        var ls = lower.getShape(level, lowerPos).bounds(); var us = upper.getShape(level, lowerPos.above()).bounds();
        expect(ls.minY == 0 && ls.maxY == 1 && us.minY == 0 && us.maxY == 1, where + ": each half fills its cell from floor to top, " + ls + " " + us);
        expect(!lower.getCollisionShape(level, lowerPos, CollisionContext.empty()).isEmpty() && !upper.getCollisionShape(level, lowerPos.above(), CollisionContext.empty()).isEmpty(), where + ": both halves collide");
        expect(lower.getLightEmission() == 10 && upper.getLightEmission() == 10, where + ": both halves glow");
        expect(AutomationGuard.structural(lower) && AutomationGuard.structural(upper), where + ": both halves are structural (shell protections apply)");
        expect(lower.getDestroySpeed(level, lowerPos) < 0 && upper.getDestroySpeed(level, lowerPos.above()) < 0, where + ": both halves are unbreakable for players");
        expect(lower.getPistonPushReaction() == net.minecraft.world.level.material.PushReaction.BLOCK && upper.getPistonPushReaction() == net.minecraft.world.level.material.PushReaction.BLOCK, where + ": pistons cannot move either half");
    }

    @GameTest(template = T, templateNamespace = "nullspace", timeoutTicks = 200)
    public static void theExitIsAWholeDoorInTheStarterRoomAndTheHall(GameTestHelper h) {
        Archive a = newArchive(h, UUID.randomUUID());
        ServerLevel level = h.getLevel();
        Room room = a.room(0);
        var in = room.interior(a.allocation());
        expect(room.width() == 9 && room.height() >= 3 && in.maxY() - in.minY() + 1 >= 3, "the starter room is at its minimum size and still three blocks tall: " + room.width() + " x " + room.height());
        BlockPos exit = starterExit(a);
        expectWholeDoor(level, exit, "starter room");
        // the terminal is the next cell east and the cells in front of the door are free
        expect(level.getBlockState(exit.east()).is(Content.TERMINAL.get()) && level.getBlockState(exit.east().above()).isAir(), "the terminal stands next to the door and the cell above it is free");
        expect(level.getBlockState(exit.south()).isAir() && level.getBlockState(exit.south().above()).isAir(), "the cells in front of the door are free");
        install(h, a);
        BlockPos hall = pos(Basement.exit(a.allocation()));
        expectWholeDoor(level, hall, "hall");
        expect(Basement.interior(a.allocation()).maxY() - hall.getY() + 1 >= 3, "the hall is at least three blocks tall over the door");
        install(h, a);
        expectWholeDoor(level, hall, "hall after a second install");
        // clicking either half is the same click
        ServerPlayer p = player(h, UUID.randomUUID(), "DoorUser");
        for (BlockPos at : new BlockPos[] {exit, exit.above()}) {
            var result = level.getBlockState(at).useWithoutItem(level, p, new BlockHitResult(Vec3.atCenterOf(at), Direction.SOUTH, at, false));
            expect(result.consumesAction(), "using " + at + " is handled by the exit: " + result);
        }
        h.succeed();
    }

    @GameTest(template = T, templateNamespace = "nullspace", timeoutTicks = 200)
    public static void theTwoHalvesOfTheExitStayPaired(GameTestHelper h) {
        Archive a = newArchive(h, UUID.randomUUID());
        ServerLevel level = h.getLevel();
        BlockPos lower = starterExit(a), upper = lower.above();
        BlockState lowerState = level.getBlockState(lower);
        ServerPlayer op = player(h, UUID.randomUUID(), "DoorOp");
        // an operator breaking the lower half takes the upper half too, and nothing drops
        lowerState.getBlock().playerWillDestroy(level, lower, lowerState, op);
        level.removeBlock(lower, false);
        expect(level.getBlockState(upper).isAir(), "breaking the lower half removes the upper one: " + level.getBlockState(upper));
        expect(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(lower).inflate(3)).isEmpty(), "and drops nothing");
        // the same from the top
        level.setBlock(lower, lowerState, 3);
        expect(ExitUpgrade.upgradeChunk(level, new ChunkPos(lower)) == 1, "the upper half comes back over a lone lower half");
        BlockState upperState = level.getBlockState(upper);
        upperState.getBlock().playerWillDestroy(level, upper, upperState, op);
        level.removeBlock(upper, false);
        expect(level.getBlockState(lower).isAir(), "breaking the upper half removes the lower one: " + level.getBlockState(lower));
        // an update that finds no lower half under an upper one removes it (as a door does)
        level.setBlock(upper, upperState, 2);
        expect(level.getBlockState(upper).is(Content.EXIT.get()), "set up an upper half over nothing");
        level.setBlock(lower, Blocks.STONE.defaultBlockState(), 3);
        expect(level.getBlockState(upper).isAir(), "an upper half over a foreign block is removed by the update");
        level.setBlock(lower, Blocks.AIR.defaultBlockState(), 3);
        expect(level.getBlockState(upper).isAir(), "nothing is left behind");
        // losing only the upper half by an edit leaves a working lower half that puts the upper one back a tick later
        level.setBlock(lower, lowerState, 3);
        level.setBlock(upper, upperState, 3);
        level.setBlock(upper, Blocks.AIR.defaultBlockState(), 3);
        expect(level.getBlockState(lower).is(Content.EXIT.get()), "the lower half stays when the upper half is lost");
        // the neighbour update queued the repair; the archive chunks of a test do not run block ticks, so the tick is run by hand
        expect(level.getBlockTicks().hasScheduledTick(lower, Content.EXIT.get()), "the lower half queued a repair tick");
        level.getBlockState(lower).tick(level, lower, level.getRandom());
        BlockState back = level.getBlockState(upper);
        expect(back.is(Content.EXIT.get()) && back.getValue(ControlBlock.HALF) == DoubleBlockHalf.UPPER && back.getValue(ControlBlock.FACING) == Direction.SOUTH, "the upper half was restored: " + back);
        h.succeed();
    }

    private static final int OLD = net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = T, templateNamespace = "nullspace", timeoutTicks = 200)
    public static void anArchiveWithTheOldSingleBlockExitGainsTheUpperHalf(GameTestHelper h) {
        Archive a = newArchive(h, UUID.randomUUID());
        ServerLevel level = h.getLevel();
        install(h, a);
        for (BlockPos lower : new BlockPos[] {starterExit(a), pos(Basement.exit(a.allocation()))}) {
            BlockPos upper = lower.above();
            // an old world: the lower half with air above it, as saved (no shape update, so no repair tick is queued either)
            level.setBlock(upper, Blocks.AIR.defaultBlockState(), OLD);
            expect(level.getBlockState(lower).is(Content.EXIT.get()) && level.getBlockState(upper).isAir(), "set up the old single block exit at " + lower);
            ServerPlayer p = player(h, UUID.randomUUID(), "OldDoor");
            expect(level.getBlockState(lower).useWithoutItem(level, p, new BlockHitResult(Vec3.atCenterOf(lower), Direction.SOUTH, lower, false)).consumesAction(), "a lone lower half still works as the exit");
            level.setBlock(upper, Blocks.AIR.defaultBlockState(), OLD);
            expect(ExitUpgrade.upgradeChunk(level, new ChunkPos(lower)) >= 1, "the load pass places the upper half at " + lower);
            expectWholeDoor(level, lower, "upgraded " + lower);
            expect(ExitUpgrade.upgradeChunk(level, new ChunkPos(lower)) == 0, "and a second pass finds nothing to do");
            // something already above the door is left alone, the lower half keeps working
            level.setBlock(upper, Blocks.STONE.defaultBlockState(), OLD);
            expect(ExitUpgrade.upgradeChunk(level, new ChunkPos(lower)) == 0 && level.getBlockState(upper).is(Blocks.STONE), "a taken cell above the door is not overwritten");
            level.setBlock(upper, Blocks.AIR.defaultBlockState(), OLD);
        }
        h.succeed();
    }
}
