package dev.nullspace.integration.opac;

import dev.nullspace.server.ArchiveService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import xaero.pac.common.event.api.v2.OPACServerAddonRegisterEvent;
import xaero.pac.common.claims.action.api.ClaimingAction;
import xaero.pac.common.server.claims.action.listener.override.api.ClaimActionPermissionOverride;
import xaero.pac.common.server.claims.action.listener.override.api.ClaimActionPermissionOverrideType;
import xaero.pac.common.server.claims.api.IServerClaimsManagerAPI;
import xaero.pac.common.server.claims.action.listener.api.IClaimActionListenerAPI;

import java.util.UUID;

// refuses new claims, force loads and anchors in the archive dimension. only this package names open parties and
// claims types
public final class OpacIntegration {
    private OpacIntegration() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(OPACServerAddonRegisterEvent.class,
            e -> e.getContext().getClaimActionListenerManagerAPI().register(new Listener()));
    }

    static final class Listener implements IClaimActionListenerAPI {
        @Override public String getName() { return "nullspace"; }

        @Override
        public ClaimActionPermissionOverride overrideClaimingActionPermission(UUID playerId, ResourceLocation dim, int x, int z, ClaimingAction action,
                                                                             IServerClaimsManagerAPI claims, ClaimActionPermissionOverride current, MinecraftServer server) {
            boolean ours =ArchiveService.DIMENSION.location().equals(dim);
            if (ours && (action == ClaimingAction.CLAIM || action == ClaimingAction.FORCELOAD || action == ClaimingAction.ANCHOR))
                return new ClaimActionPermissionOverride(ClaimActionPermissionOverrideType.FORBID, Component.translatable("message.nullspace.claims_forbidden"));
            return current;
        }

        @Override
        public void handleSuccessfulClaimingAction(UUID playerId, ResourceLocation dim, int x, int z, ClaimingAction action, IServerClaimsManagerAPI claims, MinecraftServer server) {}
    }
}
