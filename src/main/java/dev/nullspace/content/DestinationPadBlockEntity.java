package dev.nullspace.content;

import dev.nullspace.server.HubPads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class DestinationPadBlockEntity extends BlockEntity {
    private String name = "";
    private String dimension = "";

    public DestinationPadBlockEntity(BlockPos pos, BlockState state) { super(Content.DESTINATION_PAD_ENTITY.get(), pos, state); }

    public String name() { return name; }
    public String dimension() { return dimension; }
    public boolean bound() { return !name.isEmpty(); }

    public boolean show(String newName, String newDimension) {
        if (name.equals(newName) && dimension.equals(newDimension)) return false;
        name = newName; dimension = newDimension;
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        return true;
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("name", name); tag.putString("dimension", dimension);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // bounded: the label is drawn from this on every client
        name = tag.getString("name"); if (name.length() > 64) name = name.substring(0, 64);
        dimension = tag.getString("dimension"); if (dimension.length() > 96) dimension = dimension.substring(0, 96);
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveCustomOnly(registries); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override public void onLoad() {
        super.onLoad();
        // not inline: the sync may change the block state (bound / unbound look), which must not happen while the chunk is still being loaded
        if (level != null && !level.isClientSide() && level.getServer() != null) {
            var lvl = level;
            lvl.getServer().execute(() -> { if (!isRemoved() && lvl.isLoaded(worldPosition)) HubPads.syncPad(lvl, worldPosition); });
        }
    }
}
