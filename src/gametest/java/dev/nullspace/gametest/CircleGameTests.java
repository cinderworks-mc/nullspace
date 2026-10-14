package dev.nullspace.gametest;

import com.mojang.authlib.GameProfile;
import dev.nullspace.core.AccessControl;
import dev.nullspace.core.Archive;
import dev.nullspace.core.Invitation;
import dev.nullspace.core.Permission;
import dev.nullspace.core.ReturnLocation;
import dev.nullspace.integration.tempad.ArchiveCircleAccess;
import dev.nullspace.server.ArchiveService;
import dev.nullspace.server.ServerConfig;
import dev.nullspace.server.StarterRooms;
import earth.terrarium.tempad.api.player_access.PlayerAccess;
import earth.terrarium.tempad.api.player_access.PlayerAccessApi;
import earth.terrarium.tempad.common.items.ScreeningDeviceAccess;
import earth.terrarium.tempad.common.registries.ModComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

// the Tempad "Archive circle" access type through Tempad's own registry
@PrefixGameTestTemplate(false)
public final class CircleGameTests {
    private static final String T = "empty";

    private static final long HOUR = 3_600_000L;

    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static ServerPlayer player(GameTestHelper h, String name) {
        return FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), name));
    }

    private static Archive archive(GameTestHelper h, ServerPlayer owner) {
        Archive archive = ArchiveService.registryOf(h.getLevel().getServer()).claim(owner.getUUID(), StarterRooms.CLASSIC);
        StarterRooms.place(h.getLevel(), archive);
        archive.markInitialized();
        return archive;
    }

    private static boolean circle(GameTestHelper h, ServerPlayer a, ServerPlayer b) {
        return PlayerAccessApi.get(ArchiveCircleAccess.ID).canAccess(h.getLevel(), a.getGameProfile(), b.getGameProfile());
    }

    private static void invite(Archive host, ServerPlayer guest, Invitation.Kind kind, long startedAt) {
        AccessControl.invite(host, host.owner(), guest.getUUID(), kind, HOUR, Set.of(Permission.ENTER), startedAt, ArchiveService.currentPolicy());
    }

    @GameTest(template = T, templateNamespace = "nullspace")
    public static void archiveCircleIsRegisteredOnceAndSortedByTempadsRule(GameTestHelper h) {
        List<ResourceLocation> ids = PlayerAccessApi.INSTANCE.getIds();
        expect(ids.stream().filter(ArchiveCircleAccess.ID::equals).count() == 1, "nullspace:guests appears exactly once in " + ids);
        expect(PlayerAccessApi.get(ArchiveCircleAccess.ID) instanceof ArchiveCircleAccess, "the registered object is ours");
        expect(ids.equals(ids.stream().sorted().toList()), "Tempad hands the ids out sorted: " + ids);
        long smaller = ids.stream().filter(id -> id.compareTo(ArchiveCircleAccess.ID) < 0).count();
        expect(ids.indexOf(ArchiveCircleAccess.ID) == smaller, "its place is decided by the sort alone (" + ids + ")");
        PlayerAccess before = PlayerAccessApi.get(ArchiveCircleAccess.ID);
        ArchiveCircleAccess.register(new ArchiveCircleAccess(() -> true, ArchiveService::now, ArchiveService::currentPolicy, ArchiveService::registryOf));
        expect(PlayerAccessApi.INSTANCE.getIds().stream().filter(ArchiveCircleAccess.ID::equals).count() == 1, "a second registration adds nothing");
        expect(PlayerAccessApi.get(ArchiveCircleAccess.ID) == before, "and keeps the first object");
        h.succeed();
    }

    @GameTest(template = T, templateNamespace = "nullspace")
    public static void archiveCircleFollowsInvitationsInBothDirections(GameTestHelper h) {
        ServerPlayer o = player(h, "circleOwner"), g = player(h, "circleGuest"), u = player(h, "circleOutsider");
        Archive host = archive(h, o);
        expect(!circle(h, o, g) && !circle(h, g, o), "nobody is invited yet");
        invite(host, g, Invitation.Kind.PERMANENT, ArchiveService.now());
        expect(circle(h, o, g) && circle(h, g, o), "invited: linked in both argument orders");
        expect(!circle(h, o, u) && !circle(h, u, o) && !circle(h, g, u) && !circle(h, u, g), "an uninvited player is linked to nobody");
        expect(!circle(h, o, o), "never linked to yourself");

        // tempad's own Screening Device path (it swaps the two profiles before calling us)
        ItemStack device = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("tempad", "screening_device")));
        expect(!device.isEmpty(), "the Screening Device exists");
        o.setItemInHand(InteractionHand.MAIN_HAND, device);
        device.getItem().use(h.getLevel(), o, InteractionHand.MAIN_HAND);
        ResourceLocation first = PlayerAccessApi.INSTANCE.getIds().get(0);
        expect(first.equals(device.get(ModComponents.INSTANCE.getAccessId())), "the first use of the Screening Device selects the first id (" + first + ")");
        device.set(ModComponents.INSTANCE.getAccessId(), ArchiveCircleAccess.ID);
        ScreeningDeviceAccess viaDevice = ScreeningDeviceAccess.Companion.create(device);
        expect(viaDevice != null, "a device with the circle selected makes an access object");
        expect(viaDevice.canAccess(h.getLevel(), o.getGameProfile(), g.getGameProfile()) && viaDevice.canAccess(h.getLevel(), g.getGameProfile(), o.getGameProfile()), "through the Screening Device: both orders link");
        expect(!viaDevice.canAccess(h.getLevel(), o.getGameProfile(), u.getGameProfile()), "through the Screening Device: the outsider does not");

        // the reverse direction: the owner of the device is the guest of the other archive
        Archive other = archive(h, u);
        invite(other, o, Invitation.Kind.PERMANENT, ArchiveService.now());
        expect(circle(h, u, o) && circle(h, o, u), "invited to somebody else's archive links in both orders as well");

        AccessControl.remove(host, o.getUUID(), g.getUUID());
        expect(!circle(h, o, g) && !circle(h, g, o), "revoked: gone at once");
        invite(host, g, Invitation.Kind.TIMED, ArchiveService.now() - 2 * HOUR); // started two hours ago, valid for one
        expect(!circle(h, o, g) && !circle(h, g, o), "expired: does not count");
        invite(host, g, Invitation.Kind.TIMED, ArchiveService.now());
        expect(circle(h, o, g), "a fresh timed invitation counts");
        invite(host, g, Invitation.Kind.SINGLE_VISIT, ArchiveService.now());
        expect(circle(h, o, g), "an unused single visit counts");
        AccessControl.enter(host, g.getUUID(), new ReturnLocation("minecraft:overworld", 0, 70, 0, 0, 0), ArchiveService.now(), ArchiveService.currentPolicy());
        expect(!circle(h, o, g) && !circle(h, g, o), "a used single visit does not count");

        // the config switch is read on every call; the id stays registered
        invite(host, g, Invitation.Kind.PERMANENT, ArchiveService.now());
        boolean was = ServerConfig.TEMPAD_CIRCLE.get();
        try {
            ServerConfig.TEMPAD_CIRCLE.set(false);
            expect(!circle(h, o, g) && !circle(h, g, o), "switched off: nobody gets through");
            expect(PlayerAccessApi.INSTANCE.getIds().contains(ArchiveCircleAccess.ID), "and the id stays registered");
            ServerConfig.TEMPAD_CIRCLE.set(true);
            expect(circle(h, o, g), "switched on again: back at the next call");
        } finally { ServerConfig.TEMPAD_CIRCLE.set(was); }

        // hidden when anything is broken: a broken clock, no level, a missing profile
        ArchiveCircleAccess broken = new ArchiveCircleAccess(() -> true, () -> { throw new IllegalStateException("clock"); }, ArchiveService::currentPolicy, ArchiveService::registryOf);
        expect(!broken.canAccess(h.getLevel(), o.getGameProfile(), g.getGameProfile()), "an exception answers no");
        expect(!broken.canAccess(null, o.getGameProfile(), g.getGameProfile()), "no level answers no");
        expect(!PlayerAccessApi.get(ArchiveCircleAccess.ID).canAccess(h.getLevel(), null, g.getGameProfile()), "a null profile answers no");
        h.succeed();
    }
}
