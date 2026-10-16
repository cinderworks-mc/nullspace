package dev.nullspace.server;

import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveRegistry;
import dev.nullspace.core.Room;
import dev.nullspace.text.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// says which room you walked into, on the action bar, once per room change. every INTERVAL ticks each player in the
// archive dimension costs one allocation lookup and a bounds check per room of that archive. a tunnel does not count
// as a change, so walking out and back into the same room stays quiet
public final class RoomBanner {
    static final int INTERVAL = 10;
    private record Where(long allocation, int room) {}
    private static final Map<UUID, Where> LAST = new HashMap<>();

    private RoomBanner() {}

    static void install(IEventBus bus) {
        bus.addListener(ServerTickEvent.Post.class, e -> { if (e.getServer().getTickCount() % INTERVAL == 0) sweep(e.getServer().getLevel(ArchiveService.DIMENSION)); });
        bus.addListener(ServerStoppedEvent.class, e -> LAST.clear());
    }

    static void sweep(ServerLevel level) {
        if (level == null) { LAST.clear(); return; }
        ArchiveRegistry registry = ArchiveService.registryOf(level.getServer());
        Map<UUID, Where> seen = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            var pos = player.blockPosition();
            Archive archive = registry.at(pos.getX(), pos.getZ()).orElse(null);
            if (archive == null) continue;
            Where last = LAST.get(player.getUUID());
            Room room = archive.roomAt(pos.getX(), pos.getY(), pos.getZ()).orElse(null);
            if (room == null) { if (last != null && last.allocation() == archive.allocationId()) seen.put(player.getUUID(), last); continue; }
            Where now = new Where(archive.allocationId(), room.id());
            if (!now.equals(last)) player.displayClientMessage(line(room), true);
            seen.put(player.getUUID(), now);
        }
        LAST.clear();
        LAST.putAll(seen);
    }

    public static Component line(Room room) {
        if (room.id() == Room.STARTER_ID) return room.name().isEmpty() ? Msg.ok("message.nullspace.room.here_start") : Msg.ok("message.nullspace.room.here_start_named", room.name());
        return room.name().isEmpty() ? Msg.ok("message.nullspace.room.here", room.id()) : Msg.ok("message.nullspace.room.here_named", room.id(), room.name());
    }
}
