package dev.nullspace.server;

import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.ArchiveRegistry;
import dev.nullspace.core.Direction;
import dev.nullspace.core.LayoutPlanner;
import dev.nullspace.core.PendingEdit;
import dev.nullspace.core.Room;
import dev.nullspace.core.ServerPolicy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.IOUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

// crash safe enlarging and annexing: validate, write a journal entry, edit the world, then record the domain change
// and clear the entry in one save. a crash in between leaves the entry with the old domain state, and recover() rolls
// the world back to match. items are only consumed after the edit completed
public final class EditJournal {
    private static final Logger LOGGER = LoggerFactory.getLogger("nullspace");
    private EditJournal() {}

    private static ArchiveSavedData data(MinecraftServer server) { return ArchiveSavedData.get(server.overworld()); }

    public static Map<UUID, PendingEdit> pending(MinecraftServer server) { return Map.copyOf(data(server).pendingEdits()); }

    private static void begin(MinecraftServer server, Archive archive, PendingEdit edit) {
        ArchiveSavedData d = data(server);
        if (d.pendingEdits().containsKey(archive.owner())) throw new ArchiveException("error.nullspace.room.recovering");
        d.pendingEdits().put(archive.owner(), edit);
        d.setDirty();
        server.overworld().getDataStorage().save();
        // NeoForge hands SavedData writes to a background IO worker; the entry must really be on disk before the first block is touched,
        // or a kill right after this point would leave a half-edited world without a journal entry.
        IOUtilities.waitUntilIOWorkerComplete();
    }

    private static void clear(MinecraftServer server, Archive archive) {
        ArchiveSavedData d = data(server);
        d.pendingEdits().remove(archive.owner());
        d.setDirty();
    }

    static volatile Runnable afterCommitHook;

    public static void expand(MinecraftServer server, ServerLevel level, Archive archive, int roomId) {
        edit(server, level, archive, roomId, LayoutPlanner.Axis.WIDTH, ServerPolicy.DEFAULT);
    }

    // widening or ceiling raise. the server caps only veto this step. an ordinary failure restores the world in
    // process, a crash is handled by recover()
    public static void edit(MinecraftServer server, ServerLevel level, Archive archive, int roomId, LayoutPlanner.Axis axis, ServerPolicy policy) {
        var plan = LayoutPlanner.previewExpansion(archive, archive.owner(), roomId, axis, policy);
        begin(server, archive, axis == LayoutPlanner.Axis.WIDTH ? PendingEdit.expand(roomId, plan.oldTier()) : PendingEdit.raise(roomId, plan.oldTier(), plan.oldHeightTier()));
        try {
            RoomBuilder.applyExpansion(level, archive, plan);
        } catch (RuntimeException e) {
            RoomBuilder.restoreExpansion(level, archive, roomId, axis);
            clear(server, archive);
            throw e;
        }
        LayoutPlanner.commitExpansion(archive, archive.owner(), plan);
        Runnable hook = afterCommitHook;
        if (hook != null) hook.run();
        clear(server, archive);
        data(server).setDirty();
    }

    // installs the hall: journal entry, hall blocks, then the flag and the cleared entry in one save. failure undoes
    // the hall in process; after a crash recover() does it while the saved domain still says not installed. items are
    // the caller's business
    public static void installHall(MinecraftServer server, ServerLevel level, Archive archive) {
        if (archive.basement().installed()) return;
        begin(server, archive, PendingEdit.basement());
        try {
            HallBuilder.build(level, archive);
        } catch (RuntimeException e) {
            HallBuilder.rollback(level, archive);
            clear(server, archive);
            throw e;
        }
        archive.installBasement(archive.owner());
        Runnable hook = afterCommitHook;
        if (hook != null) hook.run();
        clear(server, archive);
        data(server).setDirty();
    }

    // the new room only enters the domain model after every block is placed
    public static Room annex(MinecraftServer server, ServerLevel level, Archive archive, int sourceId, Direction direction, int maxRooms) {
        var plan = LayoutPlanner.previewAnnex(archive, archive.owner(), sourceId, direction, maxRooms);
        PendingEdit edit = PendingEdit.annex(plan);
        begin(server, archive, edit);
        try {
            RoomBuilder.applyAnnex(level, archive, archive.room(sourceId), new Room(plan.roomId(), plan.gridX(), plan.gridZ(), 0), direction);
        } catch (RuntimeException e) {
            RoomBuilder.rollbackAnnex(level, archive, sourceId, direction, plan.roomId(), plan.gridX(), plan.gridZ());
            clear(server, archive);
            throw e;
        }
        Room added = LayoutPlanner.commitAnnex(archive, archive.owner(), plan, maxRooms);
        clear(server, archive);
        data(server).setDirty();
        return added;
    }

    // operator removal of a leaf room, journaled like the other edits. callers validate (empty interior, no entities,
    // operator rights). order: journal entry, clear shell and tunnel, drop the room from the domain, seal the
    // neighbour's doorway, clear the entry. failing before the domain change restores the world in process; a crash
    // is handled by recover()
    public static void removeLeaf(MinecraftServer server, ServerLevel level, Archive archive, int leafId) {
        Room leaf = archive.room(leafId);
        if (leafId == 0) throw new ArchiveException("error.nullspace.room.starter_protected");
        if (leaf.connections().size() != 1) throw new ArchiveException("error.nullspace.room.leaf_only");
        Direction towardsNeighbour = leaf.connections().keySet().iterator().next();
        int neighbourId = leaf.connections().get(towardsNeighbour);
        Room neighbour = archive.room(neighbourId);
        Direction towardsLeaf = towardsNeighbour.opposite();
        begin(server, archive, PendingEdit.remove(neighbourId, leafId, leaf.tier(), leaf.heightTier(), towardsLeaf, leaf.gridX(), leaf.gridZ()));
        try {
            RoomBuilder.clearRemovedRoom(level, archive, leaf, neighbour, towardsLeaf);
        } catch (RuntimeException e) {
            RoomBuilder.restoreRemoval(level, archive, neighbourId, leafId);
            clear(server, archive);
            throw e;
        }
        LayoutPlanner.removeLeafForRecovery(archive, leafId, true, true);
        try {
            RoomBuilder.sealDoorway(level, archive, neighbour, towardsLeaf);
        } catch (RuntimeException e) {
            // the domain change is already made: retry the idempotent seal once; if that fails too the journal entry stays and the restart finishes it.
            LOGGER.error("Sealing the doorway after removing room {} failed; retrying", leafId, e);
            RoomBuilder.sealDoorway(level, archive, neighbour, towardsLeaf);
        }
        clear(server, archive);
        data(server).setDirty();
    }

    // rolls every unfinished edit back to the domain state and returns how many it handled. entries whose archive or
    // level is missing are kept for a later retry
    public static int recover(MinecraftServer server, ServerLevel level) { return recover(server, level, data(server)); }

    private static int recover(MinecraftServer server, ServerLevel level, ArchiveSavedData d) {
        int handled = 0;
        for (var entry : new ArrayList<>(d.pendingEdits().entrySet())) {
            Archive archive = d.registry().find(entry.getKey()).orElse(null);
            PendingEdit edit = entry.getValue();
            if (archive != null && edit.alreadyApplied(archive) && edit.kind() == PendingEdit.Kind.REMOVE && level != null) {
                // the room is gone from the saved domain but the world cleanup may be unfinished: finish it (idempotent).
                Room gone = new Room(edit.newRoomId(), edit.gridX(), edit.gridZ(), edit.oldTier(), edit.oldHeightTier());
                if (archive.rooms().containsKey(edit.roomId())) {
                    Room neighbour = archive.room(edit.roomId());
                    RoomBuilder.clearRemovedRoom(level, archive, gone, neighbour, edit.direction());
                    RoomBuilder.sealDoorway(level, archive, neighbour, edit.direction());
                }
                LOGGER.warn("Finished an unfinished room removal in the archive of {}", entry.getKey());
            }
            if (archive != null && !edit.alreadyApplied(archive)) {
                if (level == null) { LOGGER.error("Unfinished {} in archive of {} cannot be rolled back: archive dimension not loaded", edit.kind(), entry.getKey()); continue; }
                switch (edit.kind()) {
                    case EXPAND -> RoomBuilder.restoreExpansion(level, archive, edit.roomId(), LayoutPlanner.Axis.WIDTH);
                    case RAISE -> RoomBuilder.restoreExpansion(level, archive, edit.roomId(), LayoutPlanner.Axis.HEIGHT);
                    case ANNEX -> RoomBuilder.rollbackAnnex(level, archive, edit.roomId(), edit.direction(), edit.newRoomId(), edit.gridX(), edit.gridZ());
                    case REMOVE -> RoomBuilder.restoreRemoval(level, archive, edit.roomId(), edit.newRoomId());
                    case BASEMENT -> HallBuilder.rollback(level, archive);
                }
                LOGGER.warn("Rolled back an unfinished {} in the archive of {} (the server stopped mid-edit); nothing was charged", edit.kind(), entry.getKey());
            }
            d.pendingEdits().remove(entry.getKey());
            d.setDirty();
            handled++;
        }
        return handled;
    }

    // test only: what a restart sees after a crash. the saved domain is the state before the edit plus the journal
    // entries written at the start. recovery runs on that and the recovered registry is returned for comparison
    public static ArchiveRegistry recoverFromSnapshot(MinecraftServer server, ServerLevel level, CompoundTag domainBeforeEdit) {
        var access = server.registryAccess();
        ArchiveSavedData loaded = ArchiveSavedData.fromTag(domainBeforeEdit, access);
        loaded.pendingEdits().putAll(data(server).pendingEdits());
        recover(server, level, loaded);
        data(server).pendingEdits().clear();
        return loaded.registry();
    }

    public static CompoundTag domainSnapshot(MinecraftServer server) { return data(server).toTag(server.registryAccess()); }

    public static Map<UUID, PendingEdit> afterSaveAndLoad(MinecraftServer server) {
        var access = server.registryAccess();
        return Map.copyOf(ArchiveSavedData.fromTag(data(server).toTag(access), access).pendingEdits());
    }
}
