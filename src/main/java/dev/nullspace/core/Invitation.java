package dev.nullspace.core;

import java.util.EnumSet;
import java.util.Set;

public record Invitation(Kind kind, long expiresAtMillis, boolean consumed, Set<Permission> permissions) {
    public enum Kind { PERMANENT, TIMED, SINGLE_VISIT }
    public Invitation {
        if (kind == null || expiresAtMillis < 0) throw ArchiveException.corrupt("invitation");
        permissions = guestPermissions(permissions);
    }
    public boolean permitsEntry(long now) {
        return permissions.contains(Permission.ENTER) && !consumed && (kind != Kind.TIMED || now < expiresAtMillis);
    }
    public Invitation consume() {
        return kind == Kind.SINGLE_VISIT ? new Invitation(kind, expiresAtMillis, true, permissions) : this;
    }
    public static Set<Permission> observerPermissions() { return Set.of(Permission.ENTER); }
    public static Set<Permission> guestPermissions(Set<Permission> permissions) {
        if (permissions == null || permissions.contains(Permission.ADMIN)) throw new ArchiveException("error.nullspace.invite.admin");
        return Set.copyOf(permissions);
    }
    public static Set<Permission> allPermissions() { return Set.copyOf(EnumSet.allOf(Permission.class)); }
}
