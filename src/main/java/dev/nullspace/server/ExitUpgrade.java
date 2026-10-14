package dev.nullspace.server;

import dev.nullspace.content.ControlBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

// archives saved when the exit was one block have a lower half and nothing above it. when such a chunk loads (the player
// arriving, a hall or room being reloaded) the upper half is put over it if the cell is free. a lone lower half works as
// an exit meanwhile, and ControlBlock also restores the upper half when it is used or its neighbour changes
public final class ExitUpgrade {
    private static final Map<ServerLevel, ArrayDeque<ChunkPos>> QUEUES = new HashMap<>();
    private static final int PER_TICK = 16;

    private ExitUpgrade() {}

    static void install(IEventBus bus) {
        bus.addListener(ChunkEvent.Load.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension().equals(ArchiveService.DIMENSION) && e.getChunk() instanceof LevelChunk chunk && hasExit(chunk))
                synchronized (QUEUES) { QUEUES.computeIfAbsent(level, l -> new ArrayDeque<>()).add(chunk.getPos()); }
        });
        bus.addListener(ServerTickEvent.Post.class, e -> { for (ServerLevel level : e.getServer().getAllLevels()) process(level); });
        bus.addListener(ServerStoppedEvent.class, e -> { synchronized (QUEUES) { QUEUES.clear(); } });
    }

    static boolean hasExit(LevelChunk chunk) {
        for (LevelChunkSection section : chunk.getSections()) if (section.maybeHas(ExitUpgrade::isExit)) return true;
        return false;
    }

    private static boolean isExit(BlockState s) { return s.getBlock() instanceof ControlBlock c && c.isExit(); }

    static void process(ServerLevel level) {
        ArrayDeque<ChunkPos> mine;
        synchronized (QUEUES) { mine = QUEUES.get(level); if (mine == null || mine.isEmpty()) return; }
        for (int n = 0; n < PER_TICK; n++) {
            ChunkPos pos;
            synchronized (QUEUES) { pos = mine.poll(); }
            if (pos == null) return;
            upgradeChunk(level, pos);
        }
    }

    public static int upgradeChunk(ServerLevel level, ChunkPos cp) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
        if (chunk == null) return 0;
        int placed = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            if (!sections[i].maybeHas(ExitUpgrade::isExit)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++)
                if (isExit(sections[i].getBlockState(x, y, z)) && ControlBlock.ensureUpper(level, new BlockPos(cp.getMinBlockX() + x, baseY + y, cp.getMinBlockZ() + z))) placed++;
        }
        return placed;
    }
}
