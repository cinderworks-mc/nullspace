package dev.nullspace.core;

import java.util.Set;
import java.util.UUID;

// one printed archive card as the archive remembers it. the item only carries the token, so revoking the record
// changes every copy at once. it can give no more than an invitation (no admin, enter included, guest limit still
// caps it)
public record CardGrant(UUID token, int number, Kind kind, long createdAtMillis, long expiresAtMillis, Set<Permission> permissions,
                        boolean holderOnly, UUID boundTo, String boundName, int uses, boolean consumed, boolean revoked) {
    public enum Kind { SINGLE_VISIT, TIMED, UNTIL_REVOKED }
    public enum State { LIVE, USED, EXPIRED, REVOKED }
    public static final int MAX_NAME = 16;

    public CardGrant {
        if (token == null || number < 1 || kind == null || createdAtMillis < 0 || expiresAtMillis < 0 || uses < 0) throw ArchiveException.corrupt("card");
        permissions = Invitation.guestPermissions(permissions);
        if (!permissions.contains(Permission.ENTER)) throw ArchiveException.corrupt("card-enter");
        if (holderOnly) { boundTo = null; boundName = null; }
        if (boundTo == null) boundName = null;
        else if (boundName == null) boundName = "";
        else if (boundName.length() > MAX_NAME) boundName = boundName.substring(0, MAX_NAME);
        if (kind == Kind.SINGLE_VISIT && uses > 0) consumed = true;
    }

    public State state(long now) {
        if (revoked) return State.REVOKED;
        if (consumed) return State.USED;
        if (kind == Kind.TIMED && now >= expiresAtMillis) return State.EXPIRED;
        return State.LIVE;
    }
    public boolean live(long now) { return state(now) == State.LIVE; }
    public boolean boundToOther(UUID player) { return boundTo != null && !boundTo.equals(player); }

    // bound to the player (unless it is a holder card), counted, and spent if single visit
    public CardGrant used(UUID player, String playerName) {
        boolean keep = holderOnly || boundTo != null;
        return new CardGrant(token, number, kind, createdAtMillis, expiresAtMillis, permissions, holderOnly,
            keep ? boundTo : player, keep ? boundName : playerName, uses + 1, kind == Kind.SINGLE_VISIT || consumed, revoked);
    }
    public CardGrant revoke() {
        return new CardGrant(token, number, kind, createdAtMillis, expiresAtMillis, permissions, holderOnly, boundTo, boundName, uses, consumed, true);
    }
}
