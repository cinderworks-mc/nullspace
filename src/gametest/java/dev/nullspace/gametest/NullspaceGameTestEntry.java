package dev.nullspace.gametest;

import dev.nullspace.Nullspace;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

// second entry point of the mod, only on the classpath of the dev runs
@Mod(Nullspace.ID)
public final class NullspaceGameTestEntry {
    public NullspaceGameTestEntry(IEventBus bus) {
        bus.addListener(NullspaceGameTestEntry::register);
    }

    private static void register(RegisterGameTestsEvent event) {
        event.register(NullspaceGameTests.class);
        event.register(ExpansionGameTests.class);
        event.register(HallGameTests.class);
        event.register(CircleGameTests.class);
    }
}
