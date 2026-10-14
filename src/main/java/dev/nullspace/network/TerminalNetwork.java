package dev.nullspace.network;

import dev.nullspace.client.TerminalClient;
import dev.nullspace.server.TerminalService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class TerminalNetwork {
    private TerminalNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("6");
        registrar.playToClient(TerminalSnapshot.TYPE, TerminalSnapshot.CODEC, (payload, context) -> {
            if (FMLEnvironment.dist.isClient()) TerminalClient.show(payload);
        });
        registrar.playToServer(SelectFinish.TYPE, SelectFinish.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) TerminalService.select(player, payload.finish());
        });
        registrar.playToServer(TerminalAction.TYPE, TerminalAction.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) TerminalService.handle(player, payload);
        });
    }

    public static void registerSelect(RegisterPayloadHandlersEvent event) {}

    public static void send(ServerPlayer player, TerminalSnapshot snapshot) { PacketDistributor.sendToPlayer(player, snapshot); }
}
