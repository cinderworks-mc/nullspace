package dev.nullspace.core;

import java.util.UUID;

public final class CarryRules {
    private CarryRules() {}

    // only an operator may carry an archive owner. a carried player is moved by the carrier, so a guest could take
    // the owner out or put them down anywhere
    public static boolean ownerMayNotBeCarried(UUID archiveOwner, UUID target, UUID carrier, boolean carrierIsOperator) {
        return !carrierIsOperator && archiveOwner.equals(target) && !carrier.equals(target);
    }
}
