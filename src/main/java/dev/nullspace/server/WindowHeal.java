package dev.nullspace.server;

import dev.nullspace.content.WindowBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

// brings windows saved by an older build up to date as soon as their chunk loads instead of waiting for a random tick
// (about a minute per window). WindowBlock.heal stays as the backstop. a chunk with windows is queued on load and
// worked a tick later, waiting up to MAX_WAIT ticks until its eight neighbours are loaded, since a window at a border
// needs them. then each window is re-resolved with the placement rule, so the result matches a random tick
public final class WindowHeal {
    public static final int MAX_WAIT = 200;
    static final int PER_TICK = 8;
    private static final Map<ServerLevel, ArrayDeque<Entry>> QUEUES = new HashMap<>();

    private record Entry(ChunkPos pos, int waited) {}

    private WindowHeal() {}

    static volatile boolean off;

    static void install(IEventBus bus) {
        bus.addListener(ChunkEvent.Load.class, e -> {
            if (!off && e.getLevel() instanceof ServerLevel level && level.dimension().equals(ArchiveService.DIMENSION) && e.getChunk() instanceof LevelChunk chunk && hasWindows(chunk)) enqueue(level, chunk.getPos(), 0);
        });
        bus.addListener(ServerTickEvent.Post.class, e -> { if (!off) for (ServerLevel level : e.getServer().getAllLevels()) process(level); });
        bus.addListener(ServerStoppedEvent.class, e -> { synchronized (QUEUES) { QUEUES.clear(); } });
    }

    static void enqueue(ServerLevel level, ChunkPos pos, int waited) {
        synchronized (QUEUES) { QUEUES.computeIfAbsent(level, l -> new ArrayDeque<>()).add(new Entry(pos, waited)); }
    }

    public static boolean hasWindows(LevelChunk chunk) {
        for (LevelChunkSection section : chunk.getSections()) if (section.maybeHas(s -> s.getBlock() instanceof WindowBlock)) return true;
        return false;
    }

    public static int process(ServerLevel level) {
        ArrayDeque<Entry> mine;
        synchronized (QUEUES) { mine = QUEUES.get(level); if (mine == null || mine.isEmpty()) return 0; }
        int healed = 0;
        for (int n = 0, size = mine.size(); n < size && n < PER_TICK; n++) {
            Entry e;
            synchronized (QUEUES) { e = mine.poll(); }
            if (e == null) break;
            if (level.getChunkSource().getChunkNow(e.pos().x, e.pos().z) == null) continue; // unloaded again: it will be queued when it loads
            if (e.waited() < MAX_WAIT && !neighboursLoaded(level, e.pos())) { enqueue(level, e.pos(), e.waited() + 1); continue; }
            healed += healChunk(level, e.pos());
        }
        return healed;
    }

    private static boolean neighboursLoaded(ServerLevel level, ChunkPos pos) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (level.getChunkSource().getChunkNow(pos.x + dx, pos.z + dz) == null) return false;
        return true;
    }

    public static int healChunk(ServerLevel level, ChunkPos cp) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
        if (chunk == null) return 0;
        int total = 0;
        for (int pass = 0; pass < 4; pass++) {
            int changed = 0;
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (!section.maybeHas(s -> s.getBlock() instanceof WindowBlock)) continue;
                int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                    if (!(section.getBlockState(x, y, z).getBlock() instanceof WindowBlock)) continue;
                    if (WindowBlock.heal(level, new BlockPos(cp.getMinBlockX() + x, baseY + y, cp.getMinBlockZ() + z))) changed++;
                }
            }
            total += changed;
            if (changed == 0) break;
        }
        return total;
    }
}
