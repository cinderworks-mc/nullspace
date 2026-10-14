package dev.nullspace.content;

import dev.nullspace.server.ServerConfig;
import dev.nullspace.text.Msg;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class ArchiveLinkItem extends Item {
    public ArchiveLinkItem(Properties p) { super(p); }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp) {
            Msg.refuse(sp, ServerConfig.ARCHIVE_LINK.get() ? Msg.hint("message.nullspace.archive_link.how") : Msg.no("message.nullspace.archive_link.off"));
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }
}
