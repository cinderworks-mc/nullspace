package dev.nullspace.server;

import dev.nullspace.content.Content;
import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.Direction;
import dev.nullspace.core.LayoutPlanner;
import dev.nullspace.core.Progression;
import dev.nullspace.core.Room;
import dev.nullspace.text.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// permanent room changes. order: validate the preview, check affordability, journal + edit the world + record the
// domain change (EditJournal), then consume items. a failure before the last step consumes nothing and leaves world
// and domain matching. previews ask the server to preload the chunks the edit touches
final class RoomOperations {
    private RoomOperations() {}

    record Preview(Component text, Progression.Cost cost) {}

    static Preview previewExpansion(ServerPlayer player, int roomId) { return previewExpansion(player, roomId, LayoutPlanner.Axis.WIDTH); }

    static Progression.Cost costOf(LayoutPlanner.ExpansionPlan plan) {
        boolean parts = ServerConfig.TEMPAD_PARTS.get();
        if (plan.axis() == LayoutPlanner.Axis.HEIGHT)
            return Progression.ceiling(plan.oldHeightTier(), ServerConfig.CEILING_FIRST.get(), ServerConfig.CEILING_SECOND.get(), parts);
        if (plan.oldTier() >= 2) return Progression.hall(ServerConfig.HALL_MODULES.get(), ServerConfig.HALL_NETHERITE.get(), parts);
        return Progression.expansion(plan.oldTier(), ServerConfig.FIRST_EXPANSION.get(), ServerConfig.SECOND_EXPANSION.get());
    }

    static Preview previewExpansion(ServerPlayer player, int roomId, LayoutPlanner.Axis axis) {
        Archive archive = requireInside(player);
        var plan = LayoutPlanner.previewExpansion(archive, player.getUUID(), roomId, axis, ArchiveService.currentPolicy());
        Progression.Cost cost = costOf(plan);
        resolveExtras(cost);
        Room room = archive.room(roomId);
        RoomBuilder.prewarm(level(player), room.bounds(archive.allocation(), plan.newWidth(), plan.newHeight(), true));
        Component text;
        if (axis == LayoutPlanner.Axis.HEIGHT)
            text = Component.translatable("message.nullspace.preview.raise", roomId, room.height(), plan.newHeight(), Msg.cost(cost, Content.EXPANDER.get()));
        else if (plan.newWidth() > Room.MAX_ROOM_WIDTH)
            text = Component.translatable("message.nullspace.preview.hall", roomId, room.width(), plan.newWidth(), Msg.cost(cost, Content.EXPANDER.get()));
        else
            text = Component.translatable("message.nullspace.preview.expand", roomId, room.width(), plan.newWidth(), Msg.cost(cost, Content.EXPANDER.get()));
        return new Preview(text, cost);
    }

    static Component expand(ServerPlayer player, int roomId) { return expand(player, roomId, LayoutPlanner.Axis.WIDTH); }

    static Component expand(ServerPlayer player, int roomId, LayoutPlanner.Axis axis) {
        Archive archive = requireInside(player);
        var policy = ArchiveService.currentPolicy();
        var plan = LayoutPlanner.previewExpansion(archive, player.getUUID(), roomId, axis, policy);
        Progression.Cost cost = costOf(plan);
        requireAffordable(player, cost, Content.EXPANDER.get());
        EditJournal.edit(player.getServer(), level(player), archive, roomId, axis, policy);
        consume(player, cost, Content.EXPANDER.get());
        ArchiveService.dirty(player.getServer());
        if (axis == LayoutPlanner.Axis.HEIGHT) return Msg.ok("message.nullspace.room.raised", roomId, plan.newHeight());
        if (plan.newWidth() > Room.MAX_ROOM_WIDTH) return Msg.ok("message.nullspace.room.hall", roomId, plan.newWidth());
        return Msg.ok("message.nullspace.room.enlarged", roomId, plan.newWidth());
    }

    static Preview previewAnnex(ServerPlayer player, int sourceId, Direction direction) {
        Archive archive = requireInside(player);
        var plan = LayoutPlanner.previewAnnex(archive, player.getUUID(), sourceId, direction, ArchiveService.currentPolicy().maxRooms());
        Progression.Cost cost = Progression.annex(archive.rooms().size(), ServerConfig.ANNEX_BASE.get(), ServerConfig.ANNEX_STEP.get());
        RoomBuilder.prewarm(level(player), plan.reservedBounds(), archive.room(sourceId).reservedBounds(archive.allocation()));
        return new Preview(Component.translatable("message.nullspace.preview.annex", plan.roomId(), Component.translatable("message.nullspace.dir." + direction.name().toLowerCase(Locale.ROOT)), sourceId, Msg.cost(cost, Content.ANNEX.get())), cost);
    }

    static Component annex(ServerPlayer player, int sourceId, Direction direction) {
        Archive archive = requireInside(player);
        int maxRooms = ArchiveService.currentPolicy().maxRooms();
        var plan = LayoutPlanner.previewAnnex(archive, player.getUUID(), sourceId, direction, maxRooms);
        Progression.Cost cost = Progression.annex(archive.rooms().size(), ServerConfig.ANNEX_BASE.get(), ServerConfig.ANNEX_STEP.get());
        requireAffordable(player, cost, Content.ANNEX.get());
        Room added = EditJournal.annex(player.getServer(), level(player), archive, sourceId, direction, maxRooms);
        consume(player, cost, Content.ANNEX.get());
        ArchiveService.dirty(player.getServer());
        return Msg.ok("message.nullspace.room.added", added.id(), Component.translatable("message.nullspace.dir." + direction.name().toLowerCase(Locale.ROOT)), sourceId);
    }

    static Component enlargeStarterWindow(ServerPlayer player) {
        Archive archive = requireInside(player);
        var result = StarterRooms.enlargeWindow(level(player), archive);
        ArchiveService.dirty(player.getServer());
        if (result.changed() == 0 && result.kept() == 0) return Msg.ok("command.nullspace.window.already");
        return Msg.ok("command.nullspace.window.done", result.changed(), result.kept() + result.doorway());
    }

    private static Archive requireInside(ServerPlayer player) {
        Archive archive = ArchiveService.ownArchive(player);
        if (!ArchiveService.DIMENSION.equals(player.level().dimension())
            || !archive.allocation().contains((int) Math.floor(player.getX()), (int) Math.floor(player.getZ())))
            throw new ArchiveException("error.nullspace.terminal.outside");
        return archive;
    }

    private static ServerLevel level(ServerPlayer player) { return player.getServer().getLevel(ArchiveService.DIMENSION); }

    private static int count(Inventory inv, Item item) {
        int n = 0;
        for (ItemStack stack : inv.items) if (stack.is(item)) n += stack.getCount();
        return n;
    }

    // items for the extra ingredients, in order. an unknown id refuses the upgrade instead of making it free
    private static List<Item> resolveExtras(Progression.Cost c) {
        List<Item> items = new ArrayList<>();
        for (Progression.Extra extra : c.extras()) {
            Item item = Msg.itemOf(extra.itemId());
            if (item == null) throw new ArchiveException("error.nullspace.cost.unavailable", extra.itemId());
            items.add(item);
        }
        return items;
    }

    static void requireAffordable(ServerPlayer player, Progression.Cost c, Item module) {
        Inventory inv = player.getInventory();
        List<Item> extras = resolveExtras(c);
        boolean enough = count(inv, module) >= c.modules() && count(inv, Items.DIAMOND) >= c.diamonds() && count(inv, Items.NETHERITE_INGOT) >= c.netherite();
        for (int i = 0; i < extras.size(); i++) enough &= count(inv, extras.get(i)) >= c.extras().get(i).count();
        if (!enough) throw new ArchiveException("error.nullspace.cost.short", Msg.cost(c, module));
    }

    static void consume(ServerPlayer player, Progression.Cost c, Item module) {
        take(player.getInventory(), module, c.modules());
        take(player.getInventory(), Items.DIAMOND, c.diamonds());
        take(player.getInventory(), Items.NETHERITE_INGOT, c.netherite());
        List<Item> extras = resolveExtras(c);
        for (int i = 0; i < extras.size(); i++) take(player.getInventory(), extras.get(i), c.extras().get(i).count());
    }

    private static void take(Inventory inv, Item item, int amount) {
        for (ItemStack stack : inv.items) {
            if (amount == 0) return;
            if (!stack.is(item)) continue;
            int n = Math.min(amount, stack.getCount());
            stack.shrink(n);
            amount -= n;
        }
    }
}
