package dev.nullspace.core;

import java.util.Objects;

// where an owner died outside their archive. unarmed from the death until they respawn inside their own archive; only
// an armed point can be used. both phases are saved so a restart cannot lose or invent one
public record RecallPoint(ReturnLocation where, long diedAtMillis, boolean armed) {
    public RecallPoint { Objects.requireNonNull(where, "where"); }
    public RecallPoint arm() { return new RecallPoint(where, diedAtMillis, true); }
}
