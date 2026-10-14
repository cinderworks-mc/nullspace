package dev.nullspace.client.sky;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class VoidSkyConfig {
    private VoidSkyConfig() {}

    public enum Quality {
        LOW(1800, 0, 0, 0, 4, 1, 0),
        MEDIUM(5400, 14, 2, 1, 6, 3, 12),
        HIGH(10800, 26, 3, 2, 8, 5, 30);
        public final int stars, glints, auroras, hazeLayers, bandRows, islands, debris;
        Quality(int stars, int glints, int auroras, int hazeLayers, int bandRows, int islands, int debris) {
            this.stars = stars; this.glints = glints; this.auroras = auroras; this.hazeLayers = hazeLayers; this.bandRows = bandRows; this.islands = islands; this.debris = debris;
        }
        public boolean twinkle() { return this != LOW; }
    }

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.EnumValue<Quality> QUALITY;
    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("The sky seen from inside the archive. Client only: changes nothing for other players and nothing about gameplay.").push("voidSky");
        ENABLED = b.comment("false = fall back to the vanilla End sky (also what you want if a shader pack or another mod misbehaves with custom skies).")
            .define("enabled", true);
        QUALITY = b.comment("LOW: dome, nebula band, static stars, large bodies. MEDIUM: + twinkling, glints, one aurora, haze, islands. HIGH: + dense stars, all auroras, two haze layers, cloud sea, debris.")
            .defineEnum("quality", Quality.MEDIUM);
        b.pop();
        SPEC = b.build();
    }

    // screenshots flip these without touching the file
    static volatile Boolean enabledOverride;
    static volatile Quality qualityOverride;

    public static boolean enabled() {
        if (enabledOverride != null) return enabledOverride;
        try { return ENABLED.get(); } catch (IllegalStateException notLoaded) { return true; }
    }

    public static Quality quality() {
        if (qualityOverride != null) return qualityOverride;
        try { return QUALITY.get(); } catch (IllegalStateException notLoaded) { return Quality.MEDIUM; }
    }
}
