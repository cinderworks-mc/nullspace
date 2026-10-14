package dev.nullspace.server;

import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.Basement;
import dev.nullspace.core.Destination;
import dev.nullspace.core.ReturnLocation;
import dev.nullspace.integration.tempad.TempadGatewayBridge;
import dev.nullspace.text.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public final class HubDestinations {
    private HubDestinations() {}

    private static final Logger LOG = LoggerFactory.getLogger("nullspace");
    public static final String[] ICONS = {"", "minecraft:grass_block", "minecraft:netherrack", "minecraft:end_stone", "minecraft:ender_pearl", "minecraft:compass",
        "minecraft:diamond", "minecraft:emerald", "minecraft:campfire", "minecraft:chest", "minecraft:oak_sapling", "minecraft:iron_pickaxe"};

    static String defaultIcon(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> "minecraft:grass_block";
            case "minecraft:the_nether" -> "minecraft:netherrack";
            case "minecraft:the_end" -> "minecraft:end_stone";
            default -> "minecraft:ender_pearl";
        };
    }

    private static int max() { return ArchiveService.hubPolicy().maxDestinations(); }

    private static Component saved(ServerPlayer owner, Archive archive, Destination d) {
        ArchiveService.dirtyData(owner.getServer());
        HubPads.refreshAll(owner.getServer(), archive);
        LOG.info("Destination '{}' of {}: {} {} {} {}", d.name(), owner.getGameProfile().getName(), d.dimension(), d.x(), d.y(), d.z());
        return Msg.ok("message.nullspace.destination.saved", d.name());
    }

    // saves the place the owner came from (the entry point recorded when they walked in), since the terminal only
    // works inside the archive. captureHere does the same from outside
    public static Component captureEntry(ServerPlayer owner, String name) {
        Archive archive = ArchiveService.ownArchive(owner);
        var visit = archive.activeVisits().get(owner.getUUID());
        if (visit == null) throw new ArchiveException("message.nullspace.destination.no_entry");
        ReturnLocation at = visit.returnLocation();
        return add(owner, archive, name, at.dimension(), at.x(), at.y(), at.z(), at.yaw());
    }

    public static Component captureHere(ServerPlayer owner, String name) {
        Archive archive = ArchiveService.ownArchive(owner);
        if (ArchiveService.DIMENSION.equals(owner.level().dimension())) throw new ArchiveException("message.nullspace.destination.outside_only");
        return add(owner, archive, name, owner.level().dimension().location().toString(), owner.getX(), owner.getY(), owner.getZ(), owner.getYRot());
    }

    public static Component importCard(ServerPlayer owner, String name) {
        Archive archive = ArchiveService.ownArchive(owner);
        TempadGatewayBridge.CardPlace place = null;
        for (ItemStack stack : new ItemStack[] {owner.getMainHandItem(), owner.getOffhandItem()}) {
            place = TempadGatewayBridge.cardPlace(stack);
            if (place != null) break;
        }
        if (place == null) throw new ArchiveException("message.nullspace.destination.no_card");
        String wanted = name == null || name.isBlank() ? sanitize(place.name()) : name;
        if (wanted.isBlank()) wanted = place.dimension().substring(place.dimension().indexOf(':') + 1);
        return add(owner, archive, unique(archive, wanted), place.dimension(), place.x(), place.y(), place.z(), place.yaw());
    }

    private static Component add(ServerPlayer owner, Archive archive, String name, String dimension, double x, double y, double z, float yaw) {
        if (ArchiveService.DIMENSION.location().toString().equals(dimension)) throw new ArchiveException("message.nullspace.destination.not_archive");
        Destination d = archive.addDestination(owner.getUUID(), name, dimension, x, y, z, yaw, defaultIcon(dimension), max());
        return saved(owner, archive, d);
    }

    public static Component rename(ServerPlayer owner, int id, String name) {
        Archive archive = ArchiveService.ownArchive(owner);
        Destination d = archive.renameDestination(owner.getUUID(), id, name);
        ArchiveService.dirtyData(owner.getServer());
        HubPads.refreshAll(owner.getServer(), archive);
        return Msg.ok("message.nullspace.destination.renamed", d.name());
    }

    public static Component delete(ServerPlayer owner, int id) {
        Archive archive = ArchiveService.ownArchive(owner);
        Destination d = archive.removeDestination(owner.getUUID(), id);
        ArchiveService.dirtyData(owner.getServer());
        HubPads.refreshAll(owner.getServer(), archive);
        return Msg.ok("message.nullspace.destination.deleted", d.name());
    }

    public static Component bind(ServerPlayer owner, int id, int slot) {
        Archive archive = ArchiveService.ownArchive(owner);
        Destination d = archive.basement().destination(id).orElseThrow(() -> new ArchiveException("error.nullspace.destination.unknown"));
        if (slot < 0) {
            int current = archive.basement().slotOf(id);
            if (current >= 0) archive.unbindPad(owner.getUUID(), current);
        } else archive.bindPad(owner.getUUID(), slot, id);
        ArchiveService.dirtyData(owner.getServer());
        HubPads.refreshAll(owner.getServer(), archive);
        return slot < 0 ? Msg.ok("message.nullspace.destination.unbound", d.name()) : Msg.ok("message.nullspace.destination.bound", d.name(), slot + 1);
    }

    public static Component icon(ServerPlayer owner, int id, int index) {
        Archive archive = ArchiveService.ownArchive(owner);
        Destination d = archive.setDestinationIcon(owner.getUUID(), id, ICONS[Math.floorMod(index, ICONS.length)]);
        ArchiveService.dirtyData(owner.getServer());
        return Msg.ok("message.nullspace.destination.icon", d.name());
    }

    static String sanitize(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[^\\p{L}\\p{N} _.'()+\\-]", " ").replaceAll(" {2,}", " ").strip();
        return s.length() > Destination.MAX_NAME ? s.substring(0, Destination.MAX_NAME).strip() : s;
    }

    static String unique(Archive archive, String base) {
        String clean = sanitize(base);
        String name = clean;
        for (int n = 2; taken(archive, name); n++) {
            String suffix = " " + n;
            name = (clean.length() + suffix.length() > Destination.MAX_NAME ? clean.substring(0, Destination.MAX_NAME - suffix.length()).strip() : clean) + suffix;
        }
        return name;
    }

    private static boolean taken(Archive archive, String name) { return archive.basement().destinations().values().stream().anyMatch(o -> o.sameName(name)); }
}
