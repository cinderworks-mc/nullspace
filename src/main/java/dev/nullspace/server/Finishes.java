package dev.nullspace.server;

import dev.nullspace.content.Content;
import dev.nullspace.content.ShellBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// the six painted finishes and the starfield window. a finish is one of our own blocks, so reskinning cannot
// give functional or obtainable blocks or drops. the held block shortcut maps ordinary blocks to a finish through
// block tags (data/nullspace/tags/block/finish), which packs can extend
public final class Finishes {
    private Finishes() {}

    public static final String WINDOW = "nullspace:window";

    public static List<String> all() {
        List<String> ids = new ArrayList<>();
        for (ShellBlock.Finish f : ShellBlock.Finish.values()) ids.add("nullspace:" + f.getSerializedName());
        ids.add(WINDOW);
        return List.copyOf(ids);
    }

    public static boolean supported(String id) { return all().contains(id); }

    public static String label(String id) {
        String n = id.substring(id.indexOf(':') + 1);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public static Component name(String id) {
        return Component.translatable("finish.nullspace." + id.substring(id.indexOf(':') + 1));
    }

    public static Optional<String> fromHeld(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)) return Optional.empty();
        Block block = item.getBlock();
        if (block == Content.SHELL.get() || block == Content.WINDOW.get()) return Optional.empty();
        for (String id : all()) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("nullspace", "finish/" + id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT)));
            if (block.builtInRegistryHolder().is(tag)) return Optional.of(id);
        }
        return Optional.empty();
    }
}
