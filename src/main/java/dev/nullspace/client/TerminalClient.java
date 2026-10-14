package dev.nullspace.client;

import dev.nullspace.network.TerminalSnapshot;
import net.minecraft.client.Minecraft;

public final class TerminalClient {
    private TerminalClient() {}

    public static void show(TerminalSnapshot snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TerminalScreen screen) screen.update(snapshot);
        else mc.setScreen(new TerminalScreen(snapshot));
    }
}
