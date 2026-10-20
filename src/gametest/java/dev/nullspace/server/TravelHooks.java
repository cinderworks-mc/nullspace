package dev.nullspace.server;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

// the gametest server has no archive dimension to stand in, so the tests name the dimension a trip starts from
public final class TravelHooks {
    private TravelHooks() {}

    public static Component refusal(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to, BlockPos at) { return TravelGuard.refusal(player, from, to, at); }
}
