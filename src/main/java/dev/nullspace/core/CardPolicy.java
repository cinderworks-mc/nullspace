package dev.nullspace.core;

public record CardPolicy(boolean enabled, int maxLive) {
    public static final CardPolicy DEFAULT = new CardPolicy(true, 8);
    public static final int MAX_LIVE_LIMIT = 32;
    public static final int MAX_RECORDS = 64;
    public CardPolicy {
        maxLive = Math.max(1, Math.min(MAX_LIVE_LIMIT, maxLive));
    }
}
