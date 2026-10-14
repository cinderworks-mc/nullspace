package dev.nullspace.content;

import dev.nullspace.client.PaletteScreen;
import dev.nullspace.server.ArchiveService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLEnvironment;

public final class SurfaceApplicatorItem extends Item {
    public SurfaceApplicatorItem(Properties p){super(p);}
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand){
        if(level.isClientSide() && FMLEnvironment.dist.isClient()) PaletteScreen.open(player.getItemInHand(hand).get(Content.SELECTED_FINISH.get()));
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide());
    }
    @Override public InteractionResult useOn(UseOnContext c){
        if(c.getPlayer() instanceof ServerPlayer sp) ArchiveService.applySurface(sp,c.getClickedPos(),c.getItemInHand());
        return InteractionResult.sidedSuccess(c.getLevel().isClientSide);
    }
}
