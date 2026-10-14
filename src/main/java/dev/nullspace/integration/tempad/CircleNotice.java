package dev.nullspace.integration.tempad;

import earth.terrarium.tempad.common.registries.ModComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

// privacy wording for the archive circle. tempad shows the access name in the action bar, tooltip and chronomark
// dropdown (about 100 px), so the name has to stay short and the line about sharing the location is added here: a
// chat line when a screening device is cycled onto the circle (tempad fires no event, so the click is noted and the
// stack checked next tick) and a tooltip line (CircleTooltipEvents)
public final class CircleNotice {
    private CircleNotice() {}

    private record Pending(ServerPlayer player, ItemStack stack, ResourceLocation before) {}
    private static final List<Pending> PENDING = new ArrayList<>();

    public static void install(IEventBus forgeBus) {
        forgeBus.addListener(CircleNotice::onUse);
        forgeBus.addListener(CircleNotice::onTick);
    }

    static ResourceLocation accessOf(ItemStack stack) { return stack.isEmpty() ? null : stack.get(ModComponents.INSTANCE.getAccessId()); }

    private static void onUse(PlayerInteractEvent.RightClickItem e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = e.getItemStack();
        if (stack.isEmpty() || !"tempad".equals(stack.getItem().builtInRegistryHolder().key().location().getNamespace())) return;
        PENDING.add(new Pending(player, stack, accessOf(stack)));
    }

    private static void onTick(ServerTickEvent.Post e) {
        if (PENDING.isEmpty()) return;
        for (Pending p : PENDING) {
            if (ArchiveCircleAccess.ID.equals(accessOf(p.stack)) && !ArchiveCircleAccess.ID.equals(p.before)) {
                p.player.sendSystemMessage(Component.translatable("message.nullspace.circle.selected").withStyle(ChatFormatting.GRAY));
            }
        }
        PENDING.clear();
    }
}
