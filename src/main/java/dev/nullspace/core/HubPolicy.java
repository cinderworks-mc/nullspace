package dev.nullspace.core;

public record HubPolicy(boolean enabled, int maxDestinations, int liftHoldTicks, int padCooldownTicks, boolean useDoor) {
    public static final int MAX_DESTINATIONS = 48, MIN_HOLD = 5, MAX_HOLD = 200, MAX_COOLDOWN = 12_000;
    public static final HubPolicy DEFAULT = new HubPolicy(true, 12, 20, 100, true);

    public HubPolicy {
        maxDestinations = Math.max(1, Math.min(MAX_DESTINATIONS, maxDestinations));
        liftHoldTicks = Math.max(MIN_HOLD, Math.min(MAX_HOLD, liftHoldTicks));
        padCooldownTicks = Math.max(0, Math.min(MAX_COOLDOWN, padCooldownTicks));
    }
}
