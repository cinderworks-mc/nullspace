package dev.nullspace.content;

import dev.nullspace.server.ArchiveService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class AccessCartridgeItem extends Item {
    public AccessCartridgeItem(Properties p){super(p);}
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand){
        if(player instanceof ServerPlayer sp) ArchiveService.useCartridge(sp, hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide);
    }
}
