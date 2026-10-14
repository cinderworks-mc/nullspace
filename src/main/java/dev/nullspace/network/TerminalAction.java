package dev.nullspace.network;

import dev.nullspace.Nullspace;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// client request; the server checks ownership, policy, costs and geometry again for each one
public record TerminalAction(Kind kind, String text, int a, int b, int c) implements CustomPacketPayload {
    public enum Kind { REFRESH, INVITE, SET_PERMISSIONS, REMOVE, PREVIEW_EXPAND, EXPAND, PREVIEW_ANNEX, ANNEX, SETTING, SAVE_PRESET, SURFACE, INSTALL_RECALL,
        // hall: install the module. destinations: capture, import a held location card, rename, delete, bind to a pad
        // (b = pad number, 0 = none), pick the icon (b = index)
        INSTALL_HALL, DEST_CAPTURE, DEST_IMPORT, DEST_RENAME, DEST_DELETE, DEST_BIND, DEST_ICON,
        // team access: toggle (a = 1 or 0), set permissions (a = permission bits, enter is added)
        TEAM_TOGGLE, TEAM_PERMISSIONS,
        // cards: print (a = permission bits, enter is added; b = kind 0 single visit, 1 timed, 2 until revoked, plus
        // 4 for whoever holds it; c = hours for a timed card), revoke (a = number), forget a dead one (a = number)
        PRINT_CARD, REVOKE_CARD, FORGET_CARD }
    public static final Type<TerminalAction> TYPE = new Type<>(Nullspace.id("terminal_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalAction> CODEC = StreamCodec.of(
        (b, p) -> { b.writeEnum(p.kind); b.writeUtf(p.text, 128); b.writeVarInt(p.a); b.writeVarInt(p.b); b.writeVarInt(p.c); },
        b -> new TerminalAction(b.readEnum(Kind.class), b.readUtf(128), b.readVarInt(), b.readVarInt(), b.readVarInt()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
