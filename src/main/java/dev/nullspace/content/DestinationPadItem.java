package dev.nullspace.content;

import dev.nullspace.core.Basement;
import dev.nullspace.server.ArchiveService;
import dev.nullspace.server.HubPads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class DestinationPadItem extends BlockItem {
    public DestinationPadItem(Block block, Properties properties) { super(block, properties); }

    @Override protected boolean canPlace(BlockPlaceContext context, BlockState state) {
        BlockPos pos = context.getClickedPos();
        return super.canPlace(context, state) && context.getLevel().dimension().equals(ArchiveService.DIMENSION)
            && Basement.slotAtNearest(pos.getX(), pos.getY(), pos.getZ()) >= 0;
    }

    @Override public InteractionResult place(BlockPlaceContext context) {
        InteractionResult result = super.place(context);
        if (!result.consumesAction() && context.getPlayer() instanceof ServerPlayer player) HubPads.refusePlacement(player, context.getClickedPos());
        return result;
    }
}
