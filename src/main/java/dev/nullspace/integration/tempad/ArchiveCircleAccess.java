package dev.nullspace.integration.tempad;

import com.mojang.authlib.GameProfile;
import dev.nullspace.core.ArchiveCircle;
import dev.nullspace.core.ArchiveRegistry;
import dev.nullspace.core.ServerPolicy;
import dev.nullspace.server.ArchiveService;
import earth.terrarium.tempad.api.player_access.PlayerAccess;
import earth.terrarium.tempad.api.player_access.PlayerAccessApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

// tempad access type nullspace:guests (archive circle): players linked by an invitation in either direction see the
// holder's live position in a screening device or at a chronomark. symmetric because tempad swaps the two profiles.
// reads live archive data on each call (tempad caches nothing) and does not decide entry, that stays with
// TempadGatewayBridge. a holder inside an archive the viewer may not enter is hidden (ArchiveCircle.visible)
public final class ArchiveCircleAccess implements PlayerAccess {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("nullspace", "guests");
    private static final Logger LOG = LoggerFactory.getLogger("nullspace");

    private final BooleanSupplier enabled;
    private final LongSupplier clock;
    private final Supplier<ServerPolicy> policy;
    private final Function<MinecraftServer, ArchiveRegistry> registries;

    private final BiFunction<MinecraftServer, UUID, ArchiveCircle.Standing> standing;

    public ArchiveCircleAccess(BooleanSupplier enabled, LongSupplier clock, Supplier<ServerPolicy> policy, Function<MinecraftServer, ArchiveRegistry> registries) {
        this(enabled, clock, policy, registries, null);
    }

    public ArchiveCircleAccess(BooleanSupplier enabled, LongSupplier clock, Supplier<ServerPolicy> policy, Function<MinecraftServer, ArchiveRegistry> registries,
                               BiFunction<MinecraftServer, UUID, ArchiveCircle.Standing> standing) {
        this.enabled = enabled; this.clock = clock; this.policy = policy; this.registries = registries;
        this.standing = standing != null ? standing : (server, id) -> standingOf(server, registries.apply(server), id);
    }

    static ArchiveCircle.Standing standingOf(MinecraftServer server, ArchiveRegistry registry, UUID id) {
        var player = server.getPlayerList().getPlayer(id);
        if (player == null || !ArchiveService.DIMENSION.equals(player.level().dimension())) return ArchiveCircle.Standing.OUTSIDE;
        return registry == null ? ArchiveCircle.Standing.UNKNOWN
            : ArchiveCircle.Standing.inside(registry.at((int) Math.floor(player.getX()), (int) Math.floor(player.getZ())).orElse(null));
    }

    public static void register(ArchiveCircleAccess access) {
        if (PlayerAccessApi.INSTANCE.getIds().contains(ID)) return;
        PlayerAccessApi.register(ID, access);
    }

    @Override public boolean canAccess(Level level, GameProfile owner, GameProfile accessor) {
        try {
            if (level == null || level.isClientSide() || owner == null || accessor == null || !enabled.getAsBoolean()) return false;
            MinecraftServer server = level.getServer();
            if (server == null) return false;
            ArchiveRegistry registry = registries.apply(server);
            // Screening Device path (PlayerHandler): Tempad swaps the profiles, so 'owner' is the traveller (viewer) and 'accessor' is the holder whose position
            // would be listed. Chronomark path: 'accessor' is the player asking to use the marker; the marker is a block, so its position cannot leak and the
            // only effect there is that a player standing in an archive the marker's owner may not enter does not get the marker listed (Tempad refuses doors
            // from inside archives anyway).
            return registry != null && ArchiveCircle.visible(id -> registry.find(id).orElse(null), id -> standing.apply(server, id),
                owner.getId(), accessor.getId(), clock.getAsLong(), policy.get());
        } catch (RuntimeException t) {
            // tempad calls this from its own screens, so a failure here must answer no instead of breaking them
            LOG.warn("Archive circle access check failed (answering no)", t);
            return false;
        }
    }
}
