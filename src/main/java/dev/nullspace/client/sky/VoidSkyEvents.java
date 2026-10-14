package dev.nullspace.client.sky;

import dev.nullspace.Nullspace;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;

@EventBusSubscriber(modid = Nullspace.ID, value = Dist.CLIENT)
public final class VoidSkyEvents {
    private VoidSkyEvents() {}

    @SubscribeEvent
    public static void register(RegisterDimensionSpecialEffectsEvent event) {
        event.register(VoidEffects.ID, new VoidEffects());
    }
}
