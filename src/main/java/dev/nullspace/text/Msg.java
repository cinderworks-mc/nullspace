package dev.nullspace.text;

import dev.nullspace.core.Permission;
import dev.nullspace.core.Progression;
import dev.nullspace.core.Translatable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// all player text goes through here: it picks the key, colour and channel, the strings live in lang/en_us.json. amber
// is success, muted red a refusal, grey a hint
public final class Msg {
    private Msg() {}

    public static final int AMBER = 0xE8A93A, RED = 0xC8574B;
    private static final Style AMBER_STYLE = Style.EMPTY.withColor(TextColor.fromRgb(AMBER));
    private static final Style RED_STYLE = Style.EMPTY.withColor(TextColor.fromRgb(RED));
    private static final Style GREY_STYLE = Style.EMPTY.withColor(TextColor.fromRgb(0x9A948A));

    public static MutableComponent ok(String key, Object... args) { return text(key, args).withStyle(AMBER_STYLE); }
    public static MutableComponent no(String key, Object... args) { return text(key, args).withStyle(RED_STYLE); }
    public static MutableComponent hint(String key, Object... args) { return text(key, args).withStyle(GREY_STYLE); }

    public static MutableComponent disabled(String feature) { return no(Translatable.disabled(feature)); }

    public static MutableComponent disabledText(String feature) { return text(Translatable.disabled(feature)); }

    private static MutableComponent text(String key, Object... args) {
        String prefix = Translatable.DISABLED + ".";
        if (!key.startsWith(prefix)) return Component.translatable(key, args);
        return Component.translatable(Translatable.DISABLED, Component.translatable("message.nullspace.feature." + key.substring(prefix.length())));
    }

    public static Component of(Throwable e) {
        if (e instanceof Translatable t) return no(t.key(), t.args());
        return no("error.nullspace.failed", String.valueOf(e.getMessage()));
    }

    public static void refuse(ServerPlayer player, Component message) { player.displayClientMessage(message, true); }

    public static void say(ServerPlayer player, Component message) { player.displayClientMessage(message, false); }

    public static MutableComponent denied(Permission permission) {
        return no("message.nullspace.denied", Component.translatable(permissionKey(permission)));
    }

    public static String permissionKey(Permission permission) { return "nullspace.perm." + permission.name().toLowerCase(Locale.ROOT); }

    public static Item itemOf(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return null;
        Item item = BuiltInRegistries.ITEM.get(key);
        return item == Items.AIR ? null : item;
    }

    public static Component cost(Progression.Cost cost, Item module) {
        List<Component> parts = new ArrayList<>();
        parts.add(Component.translatable("message.nullspace.cost.item", cost.modules(), module.getDescription()));
        if (cost.diamonds() > 0) parts.add(Component.translatable("message.nullspace.cost.item", cost.diamonds(), Items.DIAMOND.getDescription()));
        if (cost.netherite() > 0) parts.add(Component.translatable("message.nullspace.cost.item", cost.netherite(), Items.NETHERITE_INGOT.getDescription()));
        for (Progression.Extra extra : cost.extras()) {
            Item item = itemOf(extra.itemId());
            // an unknown id is shown as typed; the upgrade itself is refused before anything is charged (RoomOperations).
            parts.add(Component.translatable("message.nullspace.cost.item", extra.count(), item == null ? Component.literal(extra.itemId()) /* i18n-ok: an item id */ : item.getDescription()));
        }
        MutableComponent joined = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) joined.append(Component.translatable("message.nullspace.cost.separator"));
            joined.append(parts.get(i));
        }
        return joined;
    }
}
