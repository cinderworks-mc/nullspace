package dev.nullspace.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// hold sneak charge of the lift. step() once per tick per player who could be riding. letting go empties it. after it
// fires the player has to release for a tick before the next ride, so a held key does not ride back and forth. server
// thread only
public final class HoldCharge {
    public enum Result { IDLE, CHARGING, FIRED }

    private final Map<UUID, int[]> state = new HashMap<>();   // [ticks charged, 1 when spent and waiting for release]

    public Result step(UUID player, boolean holding, int needed) {
        if (needed < 1) throw ArchiveException.corrupt("hold-ticks");
        int[] s = state.get(player);
        if (!holding) { if (s != null) state.remove(player); return Result.IDLE; }
        if (s == null) { s = new int[2]; state.put(player, s); }
        if (s[1] == 1) return Result.IDLE;   // already rode: wait for release
        s[0]++;
        if (s[0] >= needed) { s[0] = 0; s[1] = 1; return Result.FIRED; }
        return Result.CHARGING;
    }

    public float progress(UUID player, int needed) {
        int[] s = state.get(player);
        return s == null || s[1] == 1 ? 0f : Math.min(1f, s[0] / (float) needed);
    }

    public void clear(UUID player) { state.remove(player); }

    public void retainOnly(Set<UUID> keep) {
        for (Iterator<UUID> it = state.keySet().iterator(); it.hasNext(); ) if (!keep.contains(it.next())) it.remove();
    }

    public int tracked() { return state.size(); }
}
