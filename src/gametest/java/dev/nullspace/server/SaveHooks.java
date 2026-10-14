package dev.nullspace.server;

import dev.nullspace.core.ArchiveRegistry;
import dev.nullspace.core.PendingEdit;
import net.minecraft.nbt.CompoundTag;

import java.util.Map;
import java.util.UUID;

// lets the gametests reach the package private saved data mapping
public final class SaveHooks {
    private SaveHooks() {}

    public record Loaded(ArchiveRegistry registry, int pendingReturns, int pendingEdits, int savedArchives) {}

    public static CompoundTag save(ArchiveRegistry registry) { return new ArchiveSavedData(registry).toTag(null); }

    public static Loaded load(CompoundTag tag) {
        ArchiveSavedData data = ArchiveSavedData.fromTag(tag, null);
        return new Loaded(data.registry(), data.pendingReturns().size(), data.pendingEdits().size(), data.toTag(null).getList("archives", 10).size());
    }

    public record Fixture(ArchiveRegistry registry, Map<UUID, PendingEdit> pendingEdits, CompoundTag resaved) {}

    public static Fixture loadFixture(CompoundTag tag) {
        ArchiveSavedData data = ArchiveSavedData.fromTag(tag, null);
        return new Fixture(data.registry(), Map.copyOf(data.pendingEdits()), data.toTag(null));
    }
}
