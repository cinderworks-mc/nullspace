package dev.nullspace.core;

import java.util.concurrent.atomic.AtomicBoolean;

// when team grants get re-checked. a team mod's events only request a re-check; the sweep does the work
public final class TeamSweep {
    public static final int INTERVAL_TICKS = 20;
    private final AtomicBoolean requested = new AtomicBoolean();
    public void request() { requested.set(true); }
    public boolean due(long tick) {
        boolean asked = requested.getAndSet(false);
        return asked || tick % INTERVAL_TICKS == 0;
    }
}
