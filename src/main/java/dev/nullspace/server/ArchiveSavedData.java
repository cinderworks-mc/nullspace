package dev.nullspace.server;

import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.Basement;
import dev.nullspace.core.CardGrant;
import dev.nullspace.core.ArchiveSettings;
import dev.nullspace.core.Allocation;
import dev.nullspace.core.Destination;
import dev.nullspace.core.Direction;
import dev.nullspace.core.Invitation;
import dev.nullspace.core.PendingEdit;
import dev.nullspace.core.Permission;
import dev.nullspace.core.RecallPoint;
import dev.nullspace.core.ReturnLocation;
import dev.nullspace.core.Room;
import dev.nullspace.core.ArchiveRegistry;
import dev.nullspace.core.Surface;
import dev.nullspace.core.TeamAccess;
import dev.nullspace.core.Visit;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ArchiveSavedData extends SavedData {
    private static final Logger LOGGER = LoggerFactory.getLogger("nullspace");
    private static final String ID = "nullspace_archives";
    // absent means a file from before the key existed; both read the same
    static final int FORMAT = 1;
    static final SavedData.Factory<ArchiveSavedData> FACTORY =
        new SavedData.Factory<>(() -> new ArchiveSavedData(new ArchiveRegistry()), ArchiveSavedData::load);

    private ArchiveRegistry registry;
    private final Map<UUID, ReturnLocation> pendingReturns = new HashMap<>();
    private final Map<UUID, CompoundTag> keptDeaths = new HashMap<>();
    private final Map<UUID, PendingEdit> pendingEdits = new LinkedHashMap<>();
    // archive records that could not be read, written back as they were so nothing is lost
    private final List<CompoundTag> unreadable = new ArrayList<>();

    ArchiveSavedData(ArchiveRegistry registry) { this.registry = registry; }

    static ArchiveSavedData get(ServerLevel overworld) { return overworld.getDataStorage().computeIfAbsent(FACTORY, ID); }

    ArchiveRegistry registry() { return registry; }
    Map<UUID, ReturnLocation> pendingReturns() { return pendingReturns; }
    // items and xp kept at death in an archive, restored on respawn. saved so a restart cannot lose them
    Map<UUID, CompoundTag> keptDeaths() { return keptDeaths; }
    Map<UUID, PendingEdit> pendingEdits() { return pendingEdits; }

    CompoundTag toTag(HolderLookup.Provider registries) { return save(new CompoundTag(), registries); }
    static ArchiveSavedData fromTag(CompoundTag tag, HolderLookup.Provider registries) { return load(tag, registries); }

    // if load throws, minecraft drops the SavedData and starts an empty one, and the next save overwrites the file:
    // every archive lost and allocation ids restarting at 0. so nothing read here may throw. a record that cannot be
    // read is kept as is (and written back for repair), its allocation id stays reserved, and the rest loads
    private static ArchiveSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        List<Archive> archives = new ArrayList<>();
        List<CompoundTag> unreadable = new ArrayList<>();
        Set<UUID> owners = new HashSet<>();
        Set<Long> allocations = new HashSet<>();
        if (tag.getInt("format") > FORMAT) LOGGER.warn("The saved archives were written by a newer Nullspace (format {}, this one reads {}); unknown parts are ignored.", tag.getInt("format"), FORMAT);
        long reserved = Math.max(0L, tag.getLong("nextAllocationId"));
        for (Tag t : tag.getList("archives", Tag.TAG_COMPOUND)) {
            CompoundTag raw = (CompoundTag) t;
            long id = raw.getLong("allocationId");
            if (id >= 0 && id <= Allocation.LAST_ID) reserved = Math.max(reserved, id + 1);
            // saved data may be damaged or hand edited: each record is read on its own so one bad one cannot cost the rest
            try {
                Archive archive = readArchive(raw);
                if (!owners.add(archive.owner()) || !allocations.add(archive.allocationId())) throw ArchiveException.corrupt("duplicate-allocation");
                archives.add(archive);
            } catch (RuntimeException e) {
                LOGGER.error("Archive record {} of the saved data is unreadable ({}); it is kept untouched and not loaded. Repair or remove it by hand.", raw.contains("owner") ? raw.get("owner") : "?", e.toString());
                unreadable.add(raw.copy());
            }
        }
        ArchiveSavedData data = new ArchiveSavedData(ArchiveRegistry.restore(reserved, archives));
        data.unreadable.addAll(unreadable);
        for (Tag t : tag.getList("pendingReturns", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag c = (CompoundTag) t;
                data.pendingReturns.put(c.getUUID("player"), readReturnLocation(c.getCompound("location")));
            } catch (RuntimeException e) { LOGGER.error("Skipping an unreadable pending return: {}", e.toString()); }
        }
        for (Tag t : tag.getList("keptDeaths", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag c = (CompoundTag) t;
                data.keptDeaths.put(c.getUUID("player"), c.getCompound("kept"));
            } catch (RuntimeException e) { LOGGER.error("Skipping an unreadable kept-death record: {}", e.toString()); }
        }
        for (Tag t : tag.getList("pendingEdits", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag c = (CompoundTag) t;
                data.pendingEdits.put(c.getUUID("owner"), new PendingEdit(PendingEdit.Kind.valueOf(c.getString("kind")), c.getInt("room"), c.getInt("oldTier"),
                    c.getInt("direction"), c.getInt("newRoom"), c.getInt("gridX"), c.getInt("gridZ"), c.getInt("oldHeightTier")));
            } catch (RuntimeException e) { LOGGER.error("Skipping an unreadable pending edit: {}", e.toString()); }
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT);
        tag.putLong("nextAllocationId", registry.nextAllocationId());
        ListTag archives = new ListTag();
        registry.archives().values().forEach(archive -> archives.add(writeArchive(archive)));
        unreadable.forEach(raw -> archives.add(raw.copy()));
        tag.put("archives", archives);
        ListTag pending = new ListTag();
        pendingReturns.forEach((player, location) -> {
            CompoundTag c = new CompoundTag(); c.putUUID("player", player); c.put("location", writeReturnLocation(location)); pending.add(c);
        });
        tag.put("pendingReturns", pending);
        ListTag kept = new ListTag();
        keptDeaths.forEach((player, value) -> { CompoundTag c = new CompoundTag(); c.putUUID("player", player); c.put("kept", value); kept.add(c); });
        tag.put("keptDeaths", kept);
        ListTag edits = new ListTag();
        pendingEdits.forEach((owner, e) -> {
            CompoundTag c = new CompoundTag(); c.putUUID("owner", owner); c.putString("kind", e.kind().name()); c.putInt("room", e.roomId());
            c.putInt("oldTier", e.oldTier()); c.putInt("direction", e.directionOrdinal()); c.putInt("newRoom", e.newRoomId());
            c.putInt("gridX", e.gridX()); c.putInt("gridZ", e.gridZ()); c.putInt("oldHeightTier", e.oldHeightTier()); edits.add(c);
        });
        tag.put("pendingEdits", edits);
        return tag;
    }

    private static CompoundTag writeArchive(Archive archive) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("owner", archive.owner());
        tag.putLong("allocationId", archive.allocationId());
        tag.putString("starterPreset", archive.starterPreset());
        tag.putBoolean("initialized", archive.initialized());
        tag.putBoolean("anchorInstalled", archive.anchorInstalled());
        tag.putBoolean("recallInstalled", archive.recallInstalled());
        RecallPoint recall = archive.recallPoint();
        if (recall != null) {
            CompoundTag r = new CompoundTag();
            r.put("where", writeReturnLocation(recall.where())); r.putLong("diedAtMillis", recall.diedAtMillis()); r.putBoolean("armed", recall.armed());
            tag.put("recall", r);
        }
        if (archive.basement().installed()) tag.put("basement", writeBasement(archive.basement()));
        tag.putBoolean("keepInventory", archive.settings().keepInventory());
        tag.putBoolean("keepXp", archive.settings().keepXp());
        tag.putBoolean("allowSpawn", archive.settings().allowSpawn());
        ListTag rooms = new ListTag();
        archive.rooms().values().forEach(room -> rooms.add(writeRoom(room)));
        tag.put("rooms", rooms);
        ListTag invitations = new ListTag();
        archive.invitations().forEach((guest, invitation) -> invitations.add(writeInvitation(guest, invitation)));
        tag.put("invitations", invitations);
        ListTag visits = new ListTag();
        archive.activeVisits().forEach((visitor, visit) -> visits.add(writeVisit(visitor, visit)));
        tag.put("visits", visits);
        ListTag presets = new ListTag();
        archive.presets().forEach((name, permissions) -> presets.add(writePreset(name, permissions)));
        tag.put("presets", presets);
        // only written once the owner has used the feature; older saves have no key and read as switched off.
        TeamAccess team = archive.teamAccess();
        if (team.enabled() || !team.permissions().equals(Set.of(Permission.ENTER))) {
            CompoundTag t = new CompoundTag();
            t.putBoolean("enabled", team.enabled());
            t.put("permissions", writePermissions(team.permissions()));
            if (team.consentKey() != null) t.putString("consentKey", team.consentKey());
            tag.put("teamAccess", t);
        }
        // cards: only written once one was printed; older saves have no key and read as no cards.
        if (!archive.cards().isEmpty() || archive.nextCardNumber() > 1) {
            CompoundTag c = new CompoundTag();
            c.putInt("next", archive.nextCardNumber());
            ListTag list = new ListTag();
            archive.cards().values().forEach(card -> list.add(writeCard(card)));
            c.put("list", list);
            tag.put("cards", c);
        }
        return tag;
    }

    private static Archive readArchive(CompoundTag tag) {
        Map<Integer, Room> rooms = new LinkedHashMap<>();
        for (Tag t : tag.getList("rooms", Tag.TAG_COMPOUND)) { Room room = readRoom((CompoundTag) t); rooms.put(room.id(), room); }
        Map<UUID, Invitation> invitations = new HashMap<>();
        for (Tag t : tag.getList("invitations", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            invitations.put(c.getUUID("guest"), readInvitation(c));
        }
        Map<UUID, Visit> visits = new HashMap<>();
        for (Tag t : tag.getList("visits", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            visits.put(c.getUUID("visitor"), readVisit(c));
        }
        Map<String, Set<Permission>> presets = new LinkedHashMap<>();
        for (Tag t : tag.getList("presets", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            presets.put(c.getString("name"), readPermissions(c.getList("permissions", Tag.TAG_STRING)));
        }
        // older saves have neither key: no module, no remembered spot. a damaged spot is dropped (it is only a
        // convenience), not the archive
        RecallPoint recall = null;
        if (tag.contains("recall", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag r = tag.getCompound("recall");
                recall = new RecallPoint(readReturnLocation(r.getCompound("where")), r.getLong("diedAtMillis"), r.getBoolean("armed"));
            } catch (RuntimeException e) { LOGGER.error("Dropping an unreadable Recall Module spot: {}", e.toString()); }
        }
        Archive archive = Archive.restore(tag.getUUID("owner"), Allocation.at(tag.getLong("allocationId")), tag.getString("starterPreset"),
            tag.getBoolean("initialized"), tag.getBoolean("anchorInstalled"),
            new ArchiveSettings(tag.getBoolean("keepInventory"), tag.getBoolean("keepXp"), tag.getBoolean("allowSpawn")),
            rooms, invitations, visits, presets);
        archive.restoreRecall(tag.getBoolean("recallInstalled"), recall);
        if (tag.contains("teamAccess", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag t = tag.getCompound("teamAccess");
                archive.restoreTeamAccess(new TeamAccess(t.getBoolean("enabled"), readPermissions(t.getList("permissions", Tag.TAG_STRING)), t.contains("consentKey") ? t.getString("consentKey") : null));
            } catch (RuntimeException e) { LOGGER.error("Dropping an unreadable team access setting (team access is off for this archive): {}", e.toString()); }
        }
        readCards(archive, tag);
        readBasement(archive, tag);
        return archive;
    }

    static CompoundTag writeCard(CardGrant card) {
        CompoundTag c = new CompoundTag();
        c.putUUID("token", card.token()); c.putInt("number", card.number()); c.putString("kind", card.kind().name());
        c.putLong("createdAtMillis", card.createdAtMillis()); c.putLong("expiresAtMillis", card.expiresAtMillis());
        c.put("permissions", writePermissions(card.permissions()));
        c.putBoolean("holderOnly", card.holderOnly());
        if (card.boundTo() != null) { c.putUUID("boundTo", card.boundTo()); c.putString("boundName", card.boundName()); }
        c.putInt("uses", card.uses()); c.putBoolean("consumed", card.consumed()); c.putBoolean("revoked", card.revoked());
        return c;
    }

    static CardGrant readCard(CompoundTag c) {
        return new CardGrant(c.getUUID("token"), c.getInt("number"), CardGrant.Kind.valueOf(c.getString("kind")), c.getLong("createdAtMillis"), c.getLong("expiresAtMillis"),
            readPermissions(c.getList("permissions", Tag.TAG_STRING)), c.getBoolean("holderOnly"),
            c.hasUUID("boundTo") ? c.getUUID("boundTo") : null, c.contains("boundName", Tag.TAG_STRING) ? c.getString("boundName") : null,
            c.getInt("uses"), c.getBoolean("consumed"), c.getBoolean("revoked"));
    }

    // older saves have no cards key, and a damaged entry must not make the archive unreadable. each card is read on
    // its own; a bad one is dropped and logged, a bad compound as a whole means no cards
    static void readCards(Archive archive, CompoundTag tag) {
        if (!tag.contains("cards")) return;
        try {
            if (!tag.contains("cards", Tag.TAG_COMPOUND)) throw new IllegalStateException("not a compound");
            CompoundTag c = tag.getCompound("cards");
            List<CardGrant> cards = new ArrayList<>();
            int bad = 0;
            for (Tag t : c.getList("list", Tag.TAG_COMPOUND)) {
                try { cards.add(readCard((CompoundTag) t)); } catch (RuntimeException e) { bad++; }
            }
            int dropped = archive.restoreCards(c.getInt("next"), cards) + bad;
            if (dropped > 0) LOGGER.error("Archive Cards of {}: {} unreadable or invalid entries were dropped", archive.owner(), dropped);
        } catch (RuntimeException e) {
            LOGGER.error("Archive Card data of {} is unreadable ({}); treating it as no cards", archive.owner(), e.toString());
        }
    }

    static CompoundTag writeBasement(Basement basement) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("installed", basement.installed());
        tag.putInt("nextId", basement.nextDestinationId());
        ListTag list = new ListTag();
        basement.destinations().values().forEach(d -> {
            CompoundTag c = new CompoundTag();
            c.putInt("id", d.id()); c.putString("name", d.name()); c.putString("dimension", d.dimension());
            c.putDouble("x", d.x()); c.putDouble("y", d.y()); c.putDouble("z", d.z()); c.putFloat("yaw", d.yaw());
            if (!d.icon().isEmpty()) c.putString("icon", d.icon());
            list.add(c);
        });
        tag.put("destinations", list);
        ListTag bound = new ListTag();
        basement.bindings().forEach((slot, id) -> { CompoundTag c = new CompoundTag(); c.putInt("slot", slot); c.putInt("destination", id); bound.add(c); });
        tag.put("bindings", bound);
        return tag;
    }

    // older saves have no basement key (not installed), and a damaged one must not make the archive unreadable. each
    // destination and binding is read on its own; a bad one is dropped and logged, a bad compound as a whole means
    // not installed
    static void readBasement(Archive archive, CompoundTag tag) {
        if (!tag.contains("basement")) return;
        try {
            if (!tag.contains("basement", Tag.TAG_COMPOUND)) throw new IllegalStateException("not a compound");
            CompoundTag b = tag.getCompound("basement");
            List<Destination> destinations = new ArrayList<>();
            int bad = 0;
            for (Tag t : b.getList("destinations", Tag.TAG_COMPOUND)) {
                try {
                    CompoundTag c = (CompoundTag) t;
                    destinations.add(new Destination(c.getInt("id"), c.getString("name"), c.getString("dimension"),
                        c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), c.getFloat("yaw"), c.getString("icon")));
                } catch (RuntimeException e) { bad++; }
            }
            Map<Integer, Integer> bindings = new LinkedHashMap<>();
            for (Tag t : b.getList("bindings", Tag.TAG_COMPOUND)) {
                try { CompoundTag c = (CompoundTag) t; bindings.put(c.getInt("slot"), c.getInt("destination")); } catch (RuntimeException e) { bad++; }
            }
            int dropped = archive.restoreBasement(b.getBoolean("installed"), b.getInt("nextId"), destinations, bindings) + bad;
            if (dropped > 0) LOGGER.error("Waypoint Hall of {}: {} unreadable or invalid entries were dropped", archive.owner(), dropped);
        } catch (RuntimeException e) {
            LOGGER.error("Waypoint Hall data of {} is unreadable ({}); treating the hall as not installed, so installing the module again repairs it", archive.owner(), e.toString());
        }
    }

    private static CompoundTag writeRoom(Room room) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", room.id()); tag.putInt("gridX", room.gridX()); tag.putInt("gridZ", room.gridZ()); tag.putInt("tier", room.tier());
        tag.putInt("heightTier", room.heightTier());
        ListTag connections = new ListTag();
        room.connections().forEach((direction, neighbor) -> {
            CompoundTag c = new CompoundTag(); c.putString("direction", direction.name()); c.putInt("neighbor", neighbor); connections.add(c);
        });
        tag.put("connections", connections);
        ListTag finishes = new ListTag();
        room.finishes().forEach((surface, finish) -> {
            CompoundTag c = new CompoundTag(); c.putString("surface", surface.name()); c.putString("finish", finish); finishes.add(c);
        });
        tag.put("finishes", finishes);
        ListTag individual = new ListTag();
        room.individualFinishes().forEach((coordinate, finish) -> {
            CompoundTag c = new CompoundTag();
            c.putString("surface", coordinate.surface().name()); c.putInt("u", coordinate.u()); c.putInt("v", coordinate.v()); c.putString("finish", finish);
            individual.add(c);
        });
        tag.put("individualFinishes", individual);
        return tag;
    }

    private static Room readRoom(CompoundTag tag) {
        Map<Direction, Integer> connections = new EnumMap<>(Direction.class);
        for (Tag t : tag.getList("connections", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t; connections.put(Direction.valueOf(c.getString("direction")), c.getInt("neighbor"));
        }
        Map<Surface, String> finishes = new EnumMap<>(Surface.class);
        for (Tag t : tag.getList("finishes", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t; finishes.put(Surface.valueOf(c.getString("surface")), c.getString("finish"));
        }
        Map<Room.FinishCoordinate, String> individual = new HashMap<>();
        for (Tag t : tag.getList("individualFinishes", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            individual.put(new Room.FinishCoordinate(Surface.valueOf(c.getString("surface")), c.getInt("u"), c.getInt("v")), c.getString("finish"));
        }
        // a save from before ceilings could be raised has no heightTier: getInt gives 0, which is the original six blocks.
        return Room.restore(tag.getInt("id"), tag.getInt("gridX"), tag.getInt("gridZ"), tag.getInt("tier"), tag.getInt("heightTier"), connections, finishes, individual);
    }

    private static CompoundTag writeInvitation(UUID guest, Invitation invitation) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("guest", guest);
        tag.putString("kind", invitation.kind().name());
        tag.putLong("expiresAtMillis", invitation.expiresAtMillis());
        tag.putBoolean("consumed", invitation.consumed());
        tag.put("permissions", writePermissions(invitation.permissions()));
        return tag;
    }

    private static Invitation readInvitation(CompoundTag tag) {
        return new Invitation(Invitation.Kind.valueOf(tag.getString("kind")), tag.getLong("expiresAtMillis"),
            tag.getBoolean("consumed"), readPermissions(tag.getList("permissions", Tag.TAG_STRING)));
    }

    private static CompoundTag writeVisit(UUID visitor, Visit visit) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("visitor", visitor);
        tag.put("returnLocation", writeReturnLocation(visit.returnLocation()));
        tag.putLong("enteredAtMillis", visit.enteredAtMillis());
        tag.put("permissions", writePermissions(visit.permissions()));
        if (visit.cardToken() != null) tag.putUUID("card", visit.cardToken());
        return tag;
    }

    private static Visit readVisit(CompoundTag tag) {
        return new Visit(readReturnLocation(tag.getCompound("returnLocation")), tag.getLong("enteredAtMillis"),
            readPermissions(tag.getList("permissions", Tag.TAG_STRING)), tag.hasUUID("card") ? tag.getUUID("card") : null);
    }

    private static CompoundTag writeReturnLocation(ReturnLocation location) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", location.dimension());
        tag.putDouble("x", location.x()); tag.putDouble("y", location.y()); tag.putDouble("z", location.z());
        tag.putFloat("yaw", location.yaw()); tag.putFloat("pitch", location.pitch());
        return tag;
    }

    private static ReturnLocation readReturnLocation(CompoundTag tag) {
        return new ReturnLocation(tag.getString("dimension"), tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"),
            tag.getFloat("yaw"), tag.getFloat("pitch"));
    }

    private static CompoundTag writePreset(String name, Set<Permission> permissions) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.put("permissions", writePermissions(permissions));
        return tag;
    }

    private static ListTag writePermissions(Set<Permission> permissions) {
        ListTag list = new ListTag();
        for (Permission permission : Permission.values()) if (permissions.contains(permission)) list.add(StringTag.valueOf(permission.name())); // enum order: the same set writes the same bytes
        return list;
    }

    private static Set<Permission> readPermissions(ListTag list) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (Tag t : list) permissions.add(Permission.valueOf(t.getAsString()));
        return permissions;
    }
}
