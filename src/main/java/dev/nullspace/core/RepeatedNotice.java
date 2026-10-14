package dev.nullspace.core;

import java.util.UUID;

// says a refusal once per window per source. a player standing in a gateway they may not use is refused every tick,
// which used to repaint the action bar line 15 to 50 times per arrival. the refusal still happens, only the message
// is limited
public final class RepeatedNotice {
    private final ActionLimiter limiter;

    public RepeatedNotice(long windowTicks) { this.limiter = new ActionLimiter(1, windowTicks); }

    public boolean mayTell(UUID player, UUID source, long tick) {
        UUID key = new UUID(player.getMostSignificantBits() ^ Long.rotateLeft(source.getMostSignificantBits(), 17) ^ 0x9E3779B97F4A7C15L,
            player.getLeastSignificantBits() ^ Long.rotateLeft(source.getLeastSignificantBits(), 31));
        return limiter.tryAcquire(key, tick);
    }
}
