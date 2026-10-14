package dev.nullspace.core;

import java.util.Set;

// the owner's team access switch. permissions is what the owner asked for (has enter, no admin); the grant is that
// capped by the server guest limit. consentKey is the team they switched it on for, and the grant only holds while
// they are still in it
public record TeamAccess(boolean enabled, Set<Permission> permissions, String consentKey) {
    public static final TeamAccess OFF = new TeamAccess(false, Set.of(Permission.ENTER), null);
    public TeamAccess {
        permissions = Invitation.guestPermissions(permissions);
        if (!permissions.contains(Permission.ENTER)) throw new ArchiveException("error.nullspace.invite.needs_enter");
        if (enabled && (consentKey == null || consentKey.isEmpty())) throw ArchiveException.corrupt("team-consent");
        if (!enabled) consentKey = null;
    }
}
