package dev.nullspace.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class AccessControl {
    private AccessControl() {}

    public static void invite(Archive archive, UUID actor, UUID guest, Invitation.Kind kind, long durationMillis,
                              Set<Permission> permissions, long now, ServerPolicy policy) {
        archive.requireOwner(actor); Objects.requireNonNull(guest); Objects.requireNonNull(kind);
        if (guest.equals(archive.owner())) throw new ArchiveException("error.nullspace.invite.self");
        Invitation.guestPermissions(permissions);
        if (!policy.allowedGuestPermissions().containsAll(permissions)) throw new ArchiveException("error.nullspace.invite.forbidden_permissions");
        if (!permissions.contains(Permission.ENTER)) throw new ArchiveException("error.nullspace.invite.needs_enter");
        if (!archive.invitations().containsKey(guest) && archive.invitations().size() >= policy.maxGuests()) throw new ArchiveException("error.nullspace.invite.guest_limit", policy.maxGuests());
        if (now < 0) throw ArchiveException.corrupt("invitation-time");
        long expiresAt = Long.MAX_VALUE;
        if (kind == Invitation.Kind.TIMED) {
            if (durationMillis < 1 || durationMillis > policy.maxInvitationDurationMillis()) throw new ArchiveException("error.nullspace.invite.duration", policy.maxInvitationDurationMillis() / 3_600_000L);
            expiresAt = Math.addExact(now, durationMillis);
        }
        archive.putInvitation(guest, new Invitation(kind, expiresAt, false, permissions));
        // replacing an invitation also replaces the guest's live action rights; admin is not granted
        Visit visiting = archive.activeVisits().get(guest);
        if (visiting != null) archive.putVisit(guest, new Visit(visiting.returnLocation(), visiting.enteredAtMillis(), permissions));
    }
    // live rights follow at once; kind, expiry and consumption stay as they were
    public static void setPermissions(Archive archive, UUID actor, UUID guest, Set<Permission> permissions, ServerPolicy policy) {
        archive.requireOwner(actor);
        Invitation existing = archive.invitations().get(guest);
        if (existing == null) throw new ArchiveException("error.nullspace.invite.not_guest");
        Invitation.guestPermissions(permissions);
        if (!policy.allowedGuestPermissions().containsAll(permissions)) throw new ArchiveException("error.nullspace.invite.forbidden_permissions");
        if (!permissions.contains(Permission.ENTER)) throw new ArchiveException("error.nullspace.invite.needs_enter");
        archive.putInvitation(guest, new Invitation(existing.kind(), existing.expiresAtMillis(), existing.consumed(), permissions));
        Visit visiting = archive.activeVisits().get(guest);
        if (visiting != null) archive.putVisit(guest, new Visit(visiting.returnLocation(), visiting.enteredAtMillis(), permissions));
    }
    public static boolean mayStayAt(Archive archive, UUID player, long now, ServerPolicy policy) {
        if (archive == null || archive.owner().equals(player)) return true;
        Visit visit = archive.activeVisits().get(player);
        return visit != null && !timeUp(archive, player, visit, now, policy);
    }
    public static boolean isOwner(Archive archive, UUID player) { return archive.owner().equals(player); }
    public static boolean mayEnter(Archive archive, UUID player, long now, ServerPolicy policy) {
        if (archive.owner().equals(player)) return true;
        Invitation invitation = decidingInvitation(archive, player, now, policy);
        return policy.allowedGuestPermissions().contains(Permission.ENTER) && invitation != null && invitation.permitsEntry(now);
    }
    public enum EntryRefusal {
        NOT_INVITED("error.nullspace.entry.not_invited"), EXPIRED("error.nullspace.entry.expired"),
        USED("error.nullspace.entry.used"), DISABLED(Translatable.disabled("guest_visits")),
        CARD_REVOKED("error.nullspace.entry.card_revoked"), CARD_EXPIRED("error.nullspace.entry.card_expired"), CARD_USED("error.nullspace.entry.card_used"),
        CARD_BOUND("error.nullspace.entry.card_bound"), CARDS_DISABLED(Translatable.disabled("cards"));
        private final String key;
        EntryRefusal(String key) { this.key = key; }
        public String key() { return key; }
    }
    public static EntryRefusal entryRefusal(Archive archive, UUID player, long now, ServerPolicy policy) {
        return entryRefusal(archive, player, now, policy, null);
    }
    // an invitation that still works decides. otherwise a usable card lets the player in, and a card that does not
    // work gets its own refusal unless a team grant covers them. an unknown token counts as no card, so guessing
    // tokens tells nothing
    public static EntryRefusal entryRefusal(Archive archive, UUID player, long now, ServerPolicy policy, UUID cardToken) {
        if (archive.owner().equals(player)) return null;
        if (cardToken != null && !invitationWorks(archive, player, now)) {
            CardGrant card = archive.cards().get(cardToken);
            if (card != null) {
                EntryRefusal refusal = cardRefusal(card, player, now, policy);
                if (refusal == null) return null;
                if (teamGrant(archive, player, policy) == null) return refusal;
            }
        }
        Invitation invitation = decidingInvitation(archive, player, now, policy);
        if (invitation == null) return EntryRefusal.NOT_INVITED;
        if (!policy.allowedGuestPermissions().contains(Permission.ENTER)) return EntryRefusal.DISABLED;
        if (invitation.consumed()) return EntryRefusal.USED;
        if (invitation.kind() == Invitation.Kind.TIMED && now >= invitation.expiresAtMillis()) return EntryRefusal.EXPIRED;
        return invitation.permitsEntry(now) ? null : EntryRefusal.NOT_INVITED;
    }
    private static boolean expired(Invitation invitation, long now) {
        return invitation.kind() == Invitation.Kind.TIMED && now >= invitation.expiresAtMillis();
    }
    private static boolean cardRunOut(CardGrant card, long now) {
        return card.kind() == CardGrant.Kind.TIMED && now >= card.expiresAtMillis();
    }
    // an expired invitation is dead: a team grant (if any) takes over, else it stays only to give the expired refusal
    private static Invitation decidingInvitation(Archive archive, UUID player, long now, ServerPolicy policy) {
        Invitation explicit = archive.invitations().get(player);
        if (explicit == null || !expired(explicit, now)) return effectiveInvitation(archive, player, policy);
        Invitation team = teamGrant(archive, player, policy);
        return team != null ? team : explicit;
    }
    private static boolean timeUp(Archive archive, UUID player, Visit visit, long now, ServerPolicy policy) {
        Invitation explicit = archive.invitations().get(player);
        if (explicit != null && !expired(explicit, now)) return false;
        if (visit.cardToken() != null) {
            CardGrant card = archive.cards().get(visit.cardToken());
            return card != null && cardRunOut(card, now);
        }
        return explicit != null && teamGrant(archive, player, policy) == null;
    }
    private static boolean invitationWorks(Archive archive, UUID player, long now) {
        Invitation invitation = archive.invitations().get(player);
        return invitation != null && !invitation.consumed() && (invitation.kind() != Invitation.Kind.TIMED || now < invitation.expiresAtMillis());
    }
    private static boolean spentOrNone(Invitation invitation) { return invitation == null || invitation.consumed(); }
    public static boolean mayEnterWithCard(Archive archive, UUID player, UUID cardToken, long now, ServerPolicy policy) {
        return entryRefusal(archive, player, now, policy, cardToken) == null;
    }
    public static Visit enter(Archive archive, UUID player, ReturnLocation returnLocation, long now, ServerPolicy policy) {
        return enter(archive, player, null, returnLocation, now, policy, null);
    }
    // the card is only spent and bound when it is what lets the player in; a working invitation outranks it and
    // leaves it alone
    public static Visit enter(Archive archive, UUID player, String playerName, ReturnLocation returnLocation, long now, ServerPolicy policy, UUID cardToken) {
        // a reconnect or movement inside the archive must not overwrite the original outside return point.
        Visit existing = archive.activeVisits().get(player);
        if (existing != null) return existing;
        EntryRefusal refusal = entryRefusal(archive, player, now, policy, cardToken);
        if (refusal != null) throw new ArchiveSecurityException(refusal.key());
        Set<Permission> rights;
        UUID viaCard = null;
        if (archive.owner().equals(player)) rights = Invitation.allPermissions();
        else {
            // an explicit invitation decides (a single visit one is spent). a team grant is computed and not stored,
            // so nothing is consumed and archive.invitations() stays untouched; entryRefusal above guarantees one of
            // the two exists
            Invitation explicit = invitationWorks(archive, player, now) ? archive.invitations().get(player) : null;
            CardGrant card = cardToken == null || explicit != null ? null : archive.cards().get(cardToken);
            if (explicit != null) {
                rights = explicit.permissions();
                archive.putInvitation(player, explicit.consume());
            } else if (card != null && cardRefusal(card, player, now, policy) == null) {
                rights = cardRights(card, policy);
                viaCard = card.token();
                archive.putCard(card.used(player, playerName == null ? "" : playerName));
            } else {
                rights = Objects.requireNonNull(teamGrant(archive, player, policy), "team grant vanished between check and entry").permissions();
            }
        }
        Visit visit = new Visit(returnLocation, now, rights, viaCard);
        archive.putVisit(player, visit);
        return visit;
    }
    public static boolean can(Archive archive, UUID player, Permission permission, long now, ServerPolicy policy) {
        if (permission == Permission.SET_SPAWN && !policy.effective(archive.settings()).allowSpawn()) return false;
        if (archive.owner().equals(player)) return true;
        if (permission == Permission.ADMIN || !policy.allowedGuestPermissions().contains(permission)) return false;
        Visit visit = archive.activeVisits().get(player);
        if (visit == null || timeUp(archive, player, visit, now, policy)) return false;
        Invitation explicit = archive.invitations().get(player);
        if (explicit != null && expired(explicit, now)) explicit = null;
        if (visit.cardToken() != null && spentOrNone(explicit)) {
            // a card visit: it lasts while the card does (revoked or removed ends it, a server switch ends it), then the recorded rights apply, limited by the guest limit above.
            CardGrant card = archive.cards().get(visit.cardToken());
            return card != null && !card.revoked() && policy.cards().enabled() && visit.permissions().contains(permission);
        }
        if (explicit == null) {
            // a team visit: the visit's recorded rights are only a snapshot, the live grant decides (and is gone once the team link is).
            Invitation grant = teamGrant(archive, player, policy);
            return grant != null && grant.permissions().contains(permission);
        }
        return visit.permissions().contains(permission);
    }
    public static boolean invitedSpawnRight(Archive archive, UUID player, ServerPolicy policy) {
        if (!policy.effective(archive.settings()).allowSpawn()) return false;
        if (archive.owner().equals(player)) return true;
        Invitation invitation = effectiveInvitation(archive, player, policy);
        return invitation != null && policy.allowedGuestPermissions().contains(Permission.SET_SPAWN) && invitation.permissions().contains(Permission.SET_SPAWN);
    }
    public enum TeamStatus {
        UNAVAILABLE,
        OFF,
        ACTIVE,
        NO_TEAM,
        // the owner is in a different team than the one they agreed to: no team access until they switch it on again
        SUSPENDED,
        TOO_LARGE
    }
    public record TeamView(TeamStatus status, String teamName, int size) {}

    public static TeamView teamView(Archive archive, ServerPolicy policy) {
        TeamPolicy teams = policy.teams();
        if (!teams.active()) return new TeamView(TeamStatus.UNAVAILABLE, null, 0);
        UUID owner = archive.owner();
        String key = teams.provider().teamKey(owner);
        if (key == null) return new TeamView(archive.teamAccess().enabled() ? TeamStatus.NO_TEAM : TeamStatus.OFF, null, 0);
        String name = teams.provider().teamName(owner);
        int size = teams.provider().teamSize(owner);
        if (!archive.teamAccess().enabled()) return new TeamView(TeamStatus.OFF, name, size);
        if (!key.equals(archive.teamAccess().consentKey())) return new TeamView(TeamStatus.SUSPENDED, name, size);
        if (size > teams.maxTeamSize()) return new TeamView(TeamStatus.TOO_LARGE, name, size);
        return new TeamView(TeamStatus.ACTIVE, name, size);
    }

    // an explicit invitation wins over the team grant (it can give more or less). the grant is worked out on each
    // call, not stored
    public static Invitation effectiveInvitation(Archive archive, UUID player, ServerPolicy policy) {
        Invitation explicit = archive.invitations().get(player);
        return explicit != null ? explicit : teamGrant(archive, player, policy);
    }

    // permanent grant of the owner's chosen permissions, capped by allowedGuestPermissions so removing one there
    // revokes it for teams at once. admin cannot be in it
    public static Invitation teamGrant(Archive archive, UUID player, ServerPolicy policy) {
        if (!policy.teams().active() || !archive.teamAccess().enabled() || archive.owner().equals(player)) return null;
        if (teamView(archive, policy).status() != TeamStatus.ACTIVE) return null;
        TeamRelation relation = policy.teams().provider().relation(archive.owner(), player);
        if (relation == null || !policy.teams().accepts(relation)) return null;
        Set<Permission> granted = EnumSet.noneOf(Permission.class);
        for (Permission p : archive.teamAccess().permissions()) if (p != Permission.ADMIN && policy.allowedGuestPermissions().contains(p)) granted.add(p);
        if (!granted.contains(Permission.ENTER)) return null;
        return new Invitation(Invitation.Kind.PERMANENT, Long.MAX_VALUE, false, granted);
    }

    public static void enableTeamAccess(Archive archive, UUID actor, ServerPolicy policy) {
        archive.requireOwner(actor);
        TeamPolicy teams = policy.teams();
        if (!teams.active()) throw new ArchiveException("error.nullspace.team.unavailable");
        String key = teams.provider().teamKey(actor);
        if (key == null) throw new ArchiveException("error.nullspace.team.no_team");
        if (teams.provider().teamSize(actor) > teams.maxTeamSize()) throw new ArchiveException("error.nullspace.team.too_large", teams.maxTeamSize());
        archive.putTeamAccess(new TeamAccess(true, archive.teamAccess().permissions(), key));
    }
    public static void disableTeamAccess(Archive archive, UUID actor) {
        archive.requireOwner(actor);
        archive.putTeamAccess(new TeamAccess(false, archive.teamAccess().permissions(), null));
    }
    public static void setTeamPermissions(Archive archive, UUID actor, Set<Permission> permissions, ServerPolicy policy) {
        archive.requireOwner(actor);
        Invitation.guestPermissions(permissions);
        if (!policy.allowedGuestPermissions().containsAll(permissions)) throw new ArchiveException("error.nullspace.invite.forbidden_permissions");
        if (!permissions.contains(Permission.ENTER)) throw new ArchiveException("error.nullspace.invite.needs_enter");
        TeamAccess old = archive.teamAccess();
        archive.putTeamAccess(new TeamAccess(old.enabled(), permissions, old.consentKey()));
    }
    // visitors who are only in because of a team grant that no longer holds. the caller ends their visit like a
    // removed guest
    public static List<UUID> teamVisitorsToEvict(Archive archive, ServerPolicy policy) {
        List<UUID> out = new ArrayList<>();
        for (UUID visitor : archive.activeVisits().keySet()) {
            if (visitor.equals(archive.owner()) || archive.invitations().containsKey(visitor) || archive.activeVisits().get(visitor).cardToken() != null) continue;
            if (teamGrant(archive, visitor, policy) == null) out.add(visitor);
        }
        return out;
    }

    public static Set<Permission> cardRights(CardGrant card, ServerPolicy policy) {
        Set<Permission> rights = EnumSet.noneOf(Permission.class);
        for (Permission p : card.permissions()) if (p != Permission.ADMIN && policy.allowedGuestPermissions().contains(p)) rights.add(p);
        return rights;
    }

    static EntryRefusal cardRefusal(CardGrant card, UUID player, long now, ServerPolicy policy) {
        if (!policy.cards().enabled()) return EntryRefusal.CARDS_DISABLED;
        if (!policy.allowedGuestPermissions().contains(Permission.ENTER)) return EntryRefusal.DISABLED;
        return switch (card.state(now)) {
            case REVOKED -> EntryRefusal.CARD_REVOKED;
            case EXPIRED -> EntryRefusal.CARD_EXPIRED;
            case USED -> EntryRefusal.CARD_USED;
            case LIVE -> card.boundToOther(player) ? EntryRefusal.CARD_BOUND : null;
        };
    }

    public static int liveCards(Archive archive, long now) {
        int n = 0;
        for (CardGrant card : archive.cards().values()) if (card.live(now)) n++;
        return n;
    }

    public static CardGrant printCard(Archive archive, UUID actor, UUID token, CardGrant.Kind kind, long durationMillis, Set<Permission> permissions,
                                      boolean holderOnly, long now, ServerPolicy policy) {
        archive.requireOwner(actor); Objects.requireNonNull(token); Objects.requireNonNull(kind);
        if (!policy.cards().enabled()) throw new ArchiveException(Translatable.disabled("cards"));
        Invitation.guestPermissions(permissions);
        if (!policy.allowedGuestPermissions().containsAll(permissions)) throw new ArchiveException("error.nullspace.invite.forbidden_permissions");
        if (!permissions.contains(Permission.ENTER)) throw new ArchiveException("error.nullspace.invite.needs_enter");
        if (now < 0) throw ArchiveException.corrupt("card-time");
        if (archive.cards().containsKey(token)) throw ArchiveException.corrupt("card-token");
        long expiresAt = Long.MAX_VALUE;
        if (kind == CardGrant.Kind.TIMED) {
            if (durationMillis < 1 || durationMillis > policy.maxInvitationDurationMillis()) throw new ArchiveException("error.nullspace.invite.duration", policy.maxInvitationDurationMillis() / 3_600_000L);
            expiresAt = Math.addExact(now, durationMillis);
        }
        if (liveCards(archive, now) >= policy.cards().maxLive()) throw new ArchiveException("error.nullspace.card.limit", policy.cards().maxLive());
        pruneDeadCards(archive, now);
        CardGrant card = new CardGrant(token, archive.takeCardNumber(), kind, now, expiresAt, permissions, holderOnly, null, null, 0, false, false);
        archive.putCard(card);
        return card;
    }

    private static void pruneDeadCards(Archive archive, long now) {
        while (archive.cards().size() >= CardPolicy.MAX_RECORDS) {
            UUID oldestDead = null;
            for (CardGrant card : archive.cards().values()) if (!card.live(now)) { oldestDead = card.token(); break; }
            if (oldestDead == null) throw new ArchiveException("error.nullspace.card.limit", CardPolicy.MAX_RECORDS);
            archive.removeCard(oldestDead);
        }
    }

    public static CardGrant cardByNumber(Archive archive, int number) {
        for (CardGrant card : archive.cards().values()) if (card.number() == number) return card;
        throw new ArchiveException("error.nullspace.card.unknown", number);
    }

    public static CardGrant revokeCard(Archive archive, UUID actor, int number, long now) {
        archive.requireOwner(actor);
        CardGrant card = cardByNumber(archive, number);
        if (!card.live(now)) throw new ArchiveException("error.nullspace.card.not_live", number);
        CardGrant revoked = card.revoke();
        archive.putCard(revoked);
        return revoked;
    }

    // after this the item gives no reason when used, only tempad's own can't open line
    public static void forgetCard(Archive archive, UUID actor, int number, long now) {
        archive.requireOwner(actor);
        CardGrant card = cardByNumber(archive, number);
        if (card.live(now)) throw new ArchiveException("error.nullspace.card.still_live", number);
        archive.removeCard(card.token());
    }

    public static List<UUID> expiredVisitors(Archive archive, long now, ServerPolicy policy) {
        List<UUID> out = new ArrayList<>();
        for (var entry : archive.activeVisits().entrySet()) {
            if (!entry.getKey().equals(archive.owner()) && timeUp(archive, entry.getKey(), entry.getValue(), now, policy)) out.add(entry.getKey());
        }
        return out;
    }

    public static List<UUID> dropExpiredInvitations(Archive archive, long now) {
        List<UUID> out = new ArrayList<>();
        for (var entry : archive.invitations().entrySet()) if (expired(entry.getValue(), now)) out.add(entry.getKey());
        for (UUID guest : out) archive.removeInvitation(guest);
        return out;
    }

    public static List<UUID> cardVisitorsToEvict(Archive archive, ServerPolicy policy) {
        List<UUID> out = new ArrayList<>();
        for (var entry : archive.activeVisits().entrySet()) {
            UUID token = entry.getValue().cardToken();
            if (token == null || entry.getKey().equals(archive.owner()) || !spentOrNone(archive.invitations().get(entry.getKey()))) continue;
            CardGrant card = archive.cards().get(token);
            if (card == null || card.revoked() || !policy.cards().enabled()) out.add(entry.getKey());
        }
        return out;
    }

    // callers keep the return location first and remove the visit only after the trip worked
    public static Optional<ReturnLocation> exit(Archive archive, UUID player) {
        Visit visit = archive.removeVisit(player);
        return visit == null ? Optional.empty() : Optional.of(visit.returnLocation());
    }
    public enum Departure { EVICT_NOW, RETURN_AT_LOGIN, NONE }

    // only a real visitor of the archive is moved. an owner revoking someone else's invitation must not teleport a
    // player who is elsewhere in the archive dimension
    public static Departure departure(Optional<ReturnLocation> ended, boolean online, boolean inArchiveDimension) {
        if (ended.isEmpty()) return Departure.NONE;
        return online && inArchiveDimension ? Departure.EVICT_NOW : Departure.RETURN_AT_LOGIN;
    }

    public static Optional<ReturnLocation> remove(Archive archive, UUID actor, UUID guest) {
        archive.requireOwner(actor);
        if (archive.owner().equals(guest)) throw new ArchiveException("error.nullspace.invite.remove_owner");
        if (!archive.invitations().containsKey(guest) && !archive.activeVisits().containsKey(guest)) throw new ArchiveException("error.nullspace.invite.not_guest");
        archive.removeInvitation(guest);
        return exit(archive, guest);
    }
}
