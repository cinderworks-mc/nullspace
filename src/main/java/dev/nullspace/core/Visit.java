package dev.nullspace.core;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// entry snapshots survive disconnects; revoking or an expired grant deletes the record. cardToken is the card the
// visitor came in with (null for invitation, team grant or owner), and such a visit lasts only while the card is not
// revoked
public record Visit(ReturnLocation returnLocation, long enteredAtMillis, Set<Permission> permissions, UUID cardToken) {
    public Visit {
        Objects.requireNonNull(returnLocation);
        if (enteredAtMillis < 0) throw ArchiveException.corrupt("entry-time");
        permissions = Set.copyOf(permissions);
    }
    public Visit(ReturnLocation returnLocation, long enteredAtMillis, Set<Permission> permissions) { this(returnLocation, enteredAtMillis, permissions, null); }
}
