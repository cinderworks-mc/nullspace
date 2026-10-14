package dev.nullspace.client.sky;

import dev.nullspace.Nullspace;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record VoidTheme(ResourceLocation id, Vec3 fog, float[][] dome, long seed, float tiltDeg, float spinRate, float bandCoreAzimuth,
                        List<Body> bodies, List<Island> islands, List<Aurora> auroras, float[] hazeRgb, float glowAz, float[] glow) {

    public record Body(String texture, float az, float el, float widthDeg, float aspect, float rate, float[] glow, float glowScale) {}

    public record Island(String texture, float az, float el, float widthDeg, float aspect, float rate, float bobDeg, float alpha, boolean near, int debris) {}

    public record Aurora(float azCentre, float azSpan, float elBase, float heightDeg, float[] bottom, float[] top, float phase, float intensity) {}

    public static final String TEX = "textures/environment/void/";

    public static final VoidTheme DEFAULT = new VoidTheme(
        Nullspace.id("default"),
        new Vec3(0.13, 0.09, 0.29),
        new float[][] { // elevation, r, g, b
            {-90, 0.022f, 0.018f, 0.058f}, {-45, 0.042f, 0.032f, 0.105f}, {-16, 0.090f, 0.068f, 0.210f}, {-4, 0.150f, 0.105f, 0.320f},
            {0, 0.190f, 0.125f, 0.390f}, {4, 0.150f, 0.105f, 0.340f}, {14, 0.100f, 0.080f, 0.255f}, {32, 0.062f, 0.060f, 0.185f},
            {62, 0.040f, 0.044f, 0.135f}, {90, 0.028f, 0.034f, 0.110f}},
        20241007L, 27f, 0.0035f, 108f,
        List.of(
            new Body("body_ringed.png", 312f, 24f, 24f, 96f / 64f, 0.0009f, new float[] {0.80f, 0.55f, 0.35f}, 2.2f),
            new Body("body_moon.png", 62f, 38f, 7.0f, 1f, 0.0016f, new float[] {0.45f, 0.55f, 0.95f}, 2.6f),
            new Body("body_teal.png", 168f, 12f, 4.6f, 1f, -0.0012f, new float[] {0.20f, 0.70f, 0.65f}, 2.8f),
            new Body("body_ember.png", 236f, 58f, 3.4f, 1f, 0.0022f, new float[] {0.90f, 0.40f, 0.20f}, 2.8f)),
        List.of(
            new Island("island_a.png", 150f, 5f, 30f, 72f / 44f, 0.0040f, 0.35f, 0.95f, true, 6),
            new Island("island_b.png", 8f, 3f, 15f, 48f / 32f, 0.0034f, 0.30f, 0.85f, false, 4),
            new Island("island_c.png", 252f, -1f, 12f, 40f / 36f, 0.0052f, 0.40f, 0.85f, false, 3),
            new Island("island_b.png", 330f, -24f, 26f, 48f / 32f, 0.0060f, 0.45f, 0.97f, true, 5),
            new Island("island_c.png", 82f, -36f, 30f, 40f / 36f, 0.0070f, 0.55f, 0.97f, true, 4)),
        List.of(
            new Aurora(4f, 150f, 10f, 30f, new float[] {0.20f, 1.00f, 0.62f}, new float[] {0.55f, 0.28f, 0.95f}, 0f, 0.80f),
            new Aurora(196f, 100f, 14f, 24f, new float[] {0.20f, 0.85f, 0.95f}, new float[] {0.80f, 0.30f, 0.80f}, 2.1f, 0.58f),
            new Aurora(112f, 70f, 7f, 20f, new float[] {0.45f, 1.00f, 0.55f}, new float[] {0.30f, 0.45f, 1.00f}, 4.0f, 0.46f)),
        new float[] {1.0f, 0.96f, 1.0f},
        205f, new float[] {0.34f, 0.15f, 0.20f});

    public static final Map<ResourceLocation, VoidTheme> REGISTRY = new LinkedHashMap<>();
    static { REGISTRY.put(DEFAULT.id(), DEFAULT); }

    // theme selection on the client; nothing sets it yet, so the default applies
    private static ResourceLocation selected = DEFAULT.id();
    public static void select(ResourceLocation id) { selected = id; }
    public static VoidTheme current() { return REGISTRY.getOrDefault(selected, DEFAULT); }
}
