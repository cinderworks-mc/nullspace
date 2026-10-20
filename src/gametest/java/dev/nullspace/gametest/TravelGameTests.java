package dev.nullspace.gametest;

import com.mojang.authlib.GameProfile;
import dev.nullspace.core.AccessControl;
import dev.nullspace.core.Archive;
import dev.nullspace.core.Invitation;
import dev.nullspace.core.Permission;
import dev.nullspace.core.ReturnLocation;
import dev.nullspace.server.ArchiveService;
import dev.nullspace.server.StarterRooms;
import dev.nullspace.server.TravelHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.UUID;

// other mods' teleports into the archive. these runs have no waystones, so its guard must not be loaded and the
// travel event alone keeps strangers out; the rules the waystones guard asks are checked here without it
@PrefixGameTestTemplate(false)
public final class TravelGameTests {
    private static final String T = "empty";

    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static ServerPlayer player(ServerLevel level, UUID id, String name) { return FakePlayerFactory.get(level, new GameProfile(id, name)); }

    private static Archive claim(GameTestHelper h, UUID owner) { return ArchiveService.registryOf(h.getLevel().getServer()).claim(owner, StarterRooms.CLASSIC); }

    private static BlockPos centre(Archive archive) { return new BlockPos(archive.originX(), 70, archive.originZ()); }

    @GameTest(template = T, templateNamespace = "nullspace")
    public static void travelGuardStillRefusesWithoutWaystones(GameTestHelper h) {
        expect(!ModList.get().isLoaded("waystones"), "this run must not have waystones");
        ServerPlayer stranger = player(h.getLevel(), UUID.randomUUID(), "Stranger");
        expect(!stranger.hasPermissions(2), "the stranger is not an operator");
        var travel = NeoForge.EVENT_BUS.post(new EntityTravelToDimensionEvent(stranger, ArchiveService.DIMENSION));
        expect(travel.isCanceled(), "travel into the archive without a gateway visit is cancelled");
        var elsewhere = NeoForge.EVENT_BUS.post(new EntityTravelToDimensionEvent(stranger, Level.NETHER));
        expect(!elsewhere.isCanceled(), "travel anywhere else is left alone");
        h.succeed();
    }

    @GameTest(template = T, templateNamespace = "nullspace")
    public static void foreignTeleportRulesMatchTheTravelGuard(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var policy = ArchiveService.currentPolicy();
        var archives = ArchiveService.DIMENSION;
        UUID ownerId = UUID.randomUUID(), otherId = UUID.randomUUID(), guestId = UUID.randomUUID();
        Archive home = claim(h, ownerId), other = claim(h, otherId);
        expect(ArchiveService.registryOf(server).at(home.originX(), home.originZ()).orElse(null) == home, "the centre lies in the archive");

        ServerPlayer stranger = player(h.getLevel(), UUID.randomUUID(), "Stranger");
        expect(ArchiveService.foreignTravelRefusal(stranger, archives, centre(home)) != null, "a stranger is refused");
        expect(ArchiveService.foreignTravelRefusal(stranger, Level.OVERWORLD, BlockPos.ZERO) == null, "trips that don't end in the archive are not ours to refuse");
        ServerPlayer owner = player(h.getLevel(), ownerId, "Owner");
        expect(ArchiveService.foreignTravelRefusal(owner, archives, centre(home)) != null, "an owner without a gateway visit is refused, like the travel event does");
        expect(TravelHooks.refusal(owner, archives, archives, centre(home)) == null, "an owner may hop into their own archive from inside the archive dimension");
        expect(TravelHooks.refusal(owner, archives, archives, centre(other)) != null, "and not into someone else's");

        AccessControl.invite(home, ownerId, guestId, Invitation.Kind.PERMANENT, 0, Set.of(Permission.ENTER), 0, policy);
        AccessControl.enter(home, guestId, new ReturnLocation("minecraft:overworld", 0, 70, 0, 0, 0), ArchiveService.now(), policy);
        ServerPlayer guest = player(h.getLevel(), guestId, "Guest");
        expect(ArchiveService.foreignTravelRefusal(guest, archives, centre(home)) == null, "a guest with a gateway visit may come back to that archive");
        expect(ArchiveService.foreignTravelRefusal(guest, archives, centre(other)) != null, "but not into another one");
        expect(TravelHooks.refusal(guest, archives, archives, centre(home)) == null, "a hop inside the visited archive is fine");
        expect(TravelHooks.refusal(guest, archives, archives, centre(other)) != null, "a hop into another archive is refused");
        h.succeed();
    }
}
