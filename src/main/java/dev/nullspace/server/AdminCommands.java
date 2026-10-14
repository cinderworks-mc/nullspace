package dev.nullspace.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.Bounds;
import dev.nullspace.core.LayoutPlanner;
import dev.nullspace.core.Room;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import dev.nullspace.text.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// operator recovery (level 3, only while ServerConfig.OP_RECOVERY is on). nothing here deletes an archive or builds:
// room removal needs a leaf room, a backup confirmation and an empty interior, and the starter room cannot go
final class AdminCommands {
    private AdminCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("admin").requires(s -> s.hasPermission(3) && ServerConfig.OP_RECOVERY.get())
            .then(Commands.literal("list").executes(c -> run(c, src -> {
                var archives = ArchiveService.registryOf(src.getServer()).archives().values();
                if (archives.isEmpty()) src.sendSuccess(() -> Msg.ok("command.nullspace.admin.none"), false);
                for (Archive a : archives) src.sendSuccess(() -> Msg.ok("command.nullspace.admin.entry", name(src, a.owner()), a.rooms().size(), a.activeVisits().size(),
                    Component.translatable(a.anchorInstalled() ? "gui.nullspace.anchor.installed" : "gui.nullspace.anchor.none"), a.allocationId(), a.originX(), a.originZ()), false);
            })))
            .then(Commands.literal("visit").then(Commands.argument("owner", GameProfileArgument.gameProfile()).executes(c -> run(c, src -> {
                Archive a = archive(c, src);
                ServerPlayer op = src.getPlayerOrException();
                op.teleportTo(src.getServer().getLevel(ArchiveService.DIMENSION), a.originX() + 0.5, Room.FLOOR_Y + 1, a.originZ() + 0.5, 0f, 0f);
            }))))
            .then(Commands.literal("evict").then(Commands.argument("visitor", GameProfileArgument.gameProfile()).executes(c -> run(c, src -> {
                GameProfile visitor = GameProfileArgument.getGameProfiles(c, "visitor").iterator().next();
                int removed = ArchiveService.adminEvict(src.getServer(), visitor.getId());
                src.sendSuccess(() -> removed == 0 ? Msg.no("command.nullspace.admin.not_visitor") : Msg.ok("command.nullspace.admin.evicted"), true);
            }))))
            .then(Commands.literal("removeroom").then(Commands.argument("owner", GameProfileArgument.gameProfile()).then(Commands.argument("room", IntegerArgumentType.integer(0))
                .then(Commands.argument("confirm", StringArgumentType.word()).executes(c -> run(c, src -> {
                    if (!"backup-confirmed".equals(StringArgumentType.getString(c, "confirm"))) throw new ArchiveException("error.nullspace.admin.confirm");
                    removeRoom(src, archive(c, src), IntegerArgumentType.getInteger(c, "room"));
                }))))));
    }

    private static String name(CommandSourceStack src, UUID id) {
        var cache = src.getServer().getProfileCache();
        return cache == null ? id.toString() : cache.get(id).map(GameProfile::getName).orElse(id.toString());
    }

    private static Archive archive(CommandContext<CommandSourceStack> c, CommandSourceStack src) throws CommandSyntaxException {
        GameProfile owner = GameProfileArgument.getGameProfiles(c, "owner").iterator().next();
        return ArchiveService.registryOf(src.getServer()).find(owner.getId()).orElseThrow(() -> new ArchiveException("error.nullspace.admin.no_archive"));
    }

    private static void removeRoom(CommandSourceStack src, Archive archive, int roomId) {
        ServerLevel level = src.getServer().getLevel(ArchiveService.DIMENSION);
        Room room = archive.room(roomId);
        if (roomId == 0) throw new ArchiveException("error.nullspace.room.starter_protected");
        if (room.connections().size() != 1) throw new ArchiveException("error.nullspace.room.leaf_only");
        int neighbourId = room.connections().values().stream().findFirst().orElseThrow(() -> new ArchiveException("error.nullspace.admin.not_connected"));
        Bounds interior = room.interior(archive.allocation());
        // the whole room and its tunnel must be loaded, entities included, before looking inside: an unloaded entity section looks empty.
        RoomBuilder.prewarm(level, room.shell(archive.allocation()), archive.room(neighbourId).shell(archive.allocation()));
        if (!entitiesLoaded(level, room.shell(archive.allocation()))) throw new ArchiveException("error.nullspace.admin.loading", roomId);
        List<BlockPos> occupied = new ArrayList<>();
        for (int x = interior.minX(); x <= interior.maxX(); x++) for (int y = interior.minY(); y <= interior.maxY(); y++) for (int z = interior.minZ(); z <= interior.maxZ(); z++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.getBlockState(p).isAir() && !RoomBuilder.structural(level.getBlockState(p))) occupied.add(p);
        }
        if (!occupied.isEmpty()) throw new ArchiveException("error.nullspace.admin.occupied", roomId, occupied.size(), occupied.get(0).toShortString());
        if (!level.getEntitiesOfClass(Entity.class, new AABB(interior.minX(), interior.minY(), interior.minZ(), interior.maxX() + 1, interior.maxY() + 1, interior.maxZ() + 1)).isEmpty())
            throw new ArchiveException("error.nullspace.admin.entities", roomId);
        EditJournal.removeLeaf(src.getServer(), level, archive, roomId);
        ArchiveService.dirty(src.getServer());
        src.sendSuccess(() -> Msg.ok("command.nullspace.admin.removed", roomId, neighbourId), true);
    }

    private static boolean entitiesLoaded(ServerLevel level, Bounds b) {
        for (int cx = Math.floorDiv(b.minX(), 16); cx <= Math.floorDiv(b.maxX(), 16); cx++)
            for (int cz = Math.floorDiv(b.minZ(), 16); cz <= Math.floorDiv(b.maxZ(), 16); cz++)
                if (!level.areEntitiesLoaded(ChunkPos.asLong(cx, cz))) return false;
        return true;
    }

    private interface Action { void run(CommandSourceStack src) throws CommandSyntaxException; }

    private static int run(CommandContext<CommandSourceStack> c, Action action) {
        try { action.run(c.getSource()); return 1; }
        catch (IllegalArgumentException | SecurityException | CommandSyntaxException e) { c.getSource().sendFailure(Msg.of(e)); }
        return 0;
    }
}
