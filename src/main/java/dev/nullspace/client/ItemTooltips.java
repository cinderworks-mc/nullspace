package dev.nullspace.client;

import dev.nullspace.Nullspace;
import dev.nullspace.content.Content;
import dev.nullspace.server.Finishes;
import dev.nullspace.text.Msg;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

// item.nullspace.<id>.tooltip.1 to .4 in the lang file are the grey lines under the name, as many as exist
@EventBusSubscriber(modid = Nullspace.ID, value = Dist.CLIENT)
public final class ItemTooltips {
    private ItemTooltips() {}

    static final int MAX_LINES = 4;

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!Nullspace.ID.equals(id.getNamespace())) return;
        String base = "item." + Nullspace.ID + "." + id.getPath() + ".tooltip.";
        for (int i = 1; i <= MAX_LINES && Language.getInstance().has(base + i); i++) event.getToolTip().add(Msg.hint(base + i));
        String finish = stack.is(Content.APPLICATOR.get()) ? stack.get(Content.SELECTED_FINISH.get()) : null;
        if (finish != null) event.getToolTip().add(Msg.hint("item.nullspace.surface_applicator.finish", Finishes.name(finish)));
    }
}
