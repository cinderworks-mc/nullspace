package dev.nullspace.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// data driven starter furnishings from data/<ns>/nullspace_presets/<name>.json. coordinates are relative to the room
// centre (x and z in -4..4) and the interior floor (y in 0..5). presets only place blocks; terminal, exit and window
// come from code. unknown optional blocks use their fallback or are skipped, and a preset naming absent mods is
// hidden
public final class PresetManager extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    public static final int MAX_BLOCKS = 128;
    private static final Set<String> RESERVED = Set.of("0,0,-4", "-1,0,-4");

    public static final PresetManager INSTANCE = new PresetManager();

    // fallbackProperties only apply to the fallback block
    public record Entry(int x, int y, int z, ResourceLocation block, ResourceLocation fallback, Map<String, String> properties, Map<String, String> fallbackProperties) {
        public Entry(int x, int y, int z, ResourceLocation block, ResourceLocation fallback, Map<String, String> properties) { this(x, y, z, block, fallback, properties, Map.of()); }
    }
    private static final Set<String> ENTRY_KEYS = Set.of("x", "y", "z", "block", "fallback", "properties", "fallbackProperties");
    public record Preset(ResourceLocation id, String displayName, String description, List<String> requiredMods, List<Entry> blocks) {
        public boolean available() { return requiredMods.stream().allMatch(ModList.get()::isLoaded); }
    }

    private volatile Map<ResourceLocation, Preset> presets = Map.of();

    private PresetManager() { super(GSON, "nullspace_presets"); }

    public Optional<Preset> get(ResourceLocation id) { return Optional.ofNullable(presets.get(id)); }

    // presets enabled in the server config whose mods are present, in config order
    public List<Preset> offered() {
        List<Preset> result = new ArrayList<>();
        for (String id : ServerConfig.PRESETS.get()) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            Preset preset = key == null ? null : presets.get(key);
            if (preset == null || !preset.available()) continue;
            if (!preset.requiredMods().isEmpty() && !ServerConfig.OPTIONAL_PRESETS.get()) continue;
            result.add(preset);
        }
        if (result.isEmpty()) get(ResourceLocation.fromNamespaceAndPath("nullspace", "classic")).ifPresent(result::add);
        return result;
    }

    @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, Preset> loaded = new LinkedHashMap<>();
        files.forEach((id, json) -> {
            try { loaded.put(id, parse(id, GsonHelper.convertToJsonObject(json, "preset"))); }
            catch (RuntimeException e) { // datapack json is untrusted: one bad preset must not hide the others
                LOGGER.error("Ignoring invalid Nullspace preset {}: {}", id, e.getMessage(), e); }
        });
        loaded.values().forEach(p -> problems(p).forEach(msg -> LOGGER.warn("Nullspace preset {}: {}", p.id(), msg)));
        presets = Map.copyOf(loaded);
        LOGGER.info("Loaded {} Nullspace starter presets: {}", loaded.size(), loaded.keySet());
    }

    public static Preset parse(ResourceLocation id, JsonObject json) {
        List<String> mods = new ArrayList<>();
        if (json.has("requires_mods")) json.getAsJsonArray("requires_mods").forEach(m -> mods.add(m.getAsString()));
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>(RESERVED);
        var array = GsonHelper.getAsJsonArray(json, "blocks");
        if (array.size() > MAX_BLOCKS) throw new IllegalArgumentException("more than " + MAX_BLOCKS + " blocks");
        for (JsonElement element : array) {
            JsonObject o = element.getAsJsonObject();
            int x = GsonHelper.getAsInt(o, "x"), y = GsonHelper.getAsInt(o, "y"), z = GsonHelper.getAsInt(o, "z");
            if (Math.abs(x) > 4 || Math.abs(z) > 4 || y < 0 || y > 5) throw new IllegalArgumentException("position outside the 9x9x6 interior: " + x + "," + y + "," + z);
            if (!seen.add(x + "," + y + "," + z)) throw new IllegalArgumentException("duplicate or reserved position " + x + "," + y + "," + z);
            ResourceLocation block = ResourceLocation.parse(GsonHelper.getAsString(o, "block"));
            ResourceLocation fallback = o.has("fallback") ? ResourceLocation.parse(GsonHelper.getAsString(o, "fallback")) : null;
            String at = x + "," + y + "," + z;
            for (String key : o.keySet()) if (!ENTRY_KEYS.contains(key)) LOGGER.warn("Preset {} entry {}: unknown key '{}' ignored (known: {})", id, at, key, ENTRY_KEYS);
            Map<String, String> props = propertyMap(o, "properties", at);
            Map<String, String> fallbackProps = propertyMap(o, "fallbackProperties", at);
            if (!fallbackProps.isEmpty() && fallback == null) LOGGER.warn("Preset {} entry {}: fallbackProperties given without a fallback block; they are never used", id, at);
            entries.add(new Entry(x, y, z, block, fallback, props, fallbackProps));
        }
        return new Preset(id, GsonHelper.getAsString(json, "display_name", id.toString()), GsonHelper.getAsString(json, "description", ""), List.copyOf(mods), List.copyOf(entries));
    }

    private static Map<String, String> propertyMap(JsonObject o, String key, String at) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!o.has(key)) return Map.of();
        if (!o.get(key).isJsonObject()) throw new IllegalArgumentException("entry " + at + ": '" + key + "' must be an object of property names to values");
        for (var p : o.getAsJsonObject(key).entrySet()) {
            if (!p.getValue().isJsonPrimitive()) throw new IllegalArgumentException("entry " + at + ": " + key + "." + p.getKey() + " must be a string, number or boolean");
            map.put(p.getKey(), p.getValue().getAsString());
        }
        return Map.copyOf(map);
    }

    public static List<String> problems(Preset preset) {
        List<String> out = new ArrayList<>();
        for (Entry e : preset.blocks()) {
            check(out, e, e.block(), e.properties(), "properties");
            check(out, e, e.fallback(), e.fallbackProperties(), "fallbackProperties");
        }
        return out;
    }

    private static void check(List<String> out, Entry e, ResourceLocation key, Map<String, String> props, String what) {
        if (key == null || props.isEmpty() || !BuiltInRegistries.BLOCK.containsKey(key)) return;
        Block block = BuiltInRegistries.BLOCK.get(key);
        for (var p : props.entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(p.getKey());
            if (property == null) out.add(e.x() + "," + e.y() + "," + e.z() + " " + what + ": " + key + " has no property '" + p.getKey() + "'");
            else if (property.getValue(p.getValue()).isEmpty()) out.add(e.x() + "," + e.y() + "," + e.z() + " " + what + ": '" + p.getValue() + "' is not a value of " + key + "#" + p.getKey());
        }
    }

    public static Optional<BlockState> resolve(Entry entry) {
        for (ResourceLocation key : new ResourceLocation[] {entry.block(), entry.fallback()}) {
            if (usable(key)) return Optional.of(stateOf(key, key.equals(entry.block()) ? entry.properties() : entry.fallbackProperties()));
        }
        LOGGER.warn("Preset block {} unavailable and no usable fallback; skipped", entry.block());
        return Optional.empty();
    }

    // the state placed when the block is missing: the fallback with its properties, or empty without a usable
    // fallback
    public static Optional<BlockState> resolveFallback(Entry entry) {
        return usable(entry.fallback()) ? Optional.of(stateOf(entry.fallback(), entry.fallbackProperties())) : Optional.empty();
    }

    private static boolean usable(ResourceLocation key) { return key != null && BuiltInRegistries.BLOCK.containsKey(key) && !"nullspace".equals(key.getNamespace()); }

    private static BlockState stateOf(ResourceLocation key, Map<String, String> properties) {
        Block block = BuiltInRegistries.BLOCK.get(key);
        BlockState state = block.defaultBlockState();
        for (var prop : properties.entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(prop.getKey());
            if (property != null) state = withValue(state, property, prop.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    public static BlockPos at(int cx, int floorY, int cz, Entry e) { return new BlockPos(cx + e.x(), floorY + e.y(), cz + e.z()); }
}
