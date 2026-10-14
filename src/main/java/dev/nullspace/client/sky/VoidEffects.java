package dev.nullspace.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.nullspace.Nullspace;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

// client look of the archive dimension (dimension type effects nullspace:void). keeps the vanilla end effects (sky
// type, bright lightmap, no ground, sunrise tint or clouds) so room lighting is unchanged and only replaces the sky
// drawing. with voidSky.enabled off it returns false from renderSky and vanilla draws the end sky
public final class VoidEffects extends DimensionSpecialEffects {
    public static final ResourceLocation ID = Nullspace.id("void");

    private final VoidSkyRenderer renderer = new VoidSkyRenderer();

    public VoidEffects() {
        super(Float.NaN, false, SkyType.END, true, false);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        return VoidSkyConfig.enabled() ? VoidTheme.current().fog() : fogColor.scale(0.15F);
    }

    @Override
    public boolean isFoggyAt(int x, int z) { return false; }

    @Override
    public float[] getSunriseColor(float timeOfDay, float partialTick) { return null; }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera, Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
        if (!VoidSkyConfig.enabled()) return false;
        setupFog.run();
        // same cases in which vanilla draws no sky: boss fog, lava, powder snow, blindness, darkness.
        FogType fluid = camera.getFluidInCamera();
        boolean blind = camera.getEntity() instanceof LivingEntity living && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
        if (isFoggy || fluid == FogType.LAVA || fluid == FogType.POWDER_SNOW || blind) return true;
        renderer.render(VoidTheme.current(), VoidSkyConfig.quality(), modelViewMatrix, projectionMatrix, ticks + partialTick);
        setupFog.run();
        return true;
    }
}
