package dev.nullspace.client;

import dev.nullspace.Nullspace;
import dev.nullspace.integration.tempad.ArchiveCircleAccess;
import earth.terrarium.tempad.common.registries.ModComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid = Nullspace.ID, value = Dist.CLIENT)
public final class CircleTooltipEvents {
    private CircleTooltipEvents() {}

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        if (!ArchiveCircleAccess.ID.equals(event.getItemStack().get(ModComponents.INSTANCE.getAccessId()))) return;
        event.getToolTip().add(Component.translatable("gui.nullspace.circle.tooltip").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
