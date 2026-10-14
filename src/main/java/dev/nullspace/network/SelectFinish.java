package dev.nullspace.network;

import dev.nullspace.Nullspace;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SelectFinish(String finish) implements CustomPacketPayload {
    public static final Type<SelectFinish> TYPE = new Type<>(Nullspace.id("select_finish"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectFinish> CODEC = StreamCodec.of((b, p) -> b.writeUtf(p.finish, 64), b -> new SelectFinish(b.readUtf(64)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
