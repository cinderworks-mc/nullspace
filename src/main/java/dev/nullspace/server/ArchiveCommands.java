package dev.nullspace.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import dev.nullspace.core.Archive;
import dev.nullspace.core.ArchiveException;
import dev.nullspace.core.Direction;
import dev.nullspace.core.Invitation;
import dev.nullspace.core.LayoutPlanner;
import dev.nullspace.core.Permission;
import dev.nullspace.core.Room;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import dev.nullspace.text.Msg;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ArchiveCommands {
    private ArchiveCommands() {}

    private interface Action { void run(ServerPlayer owner) throws CommandSyntaxException; }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("nullspace");
        root.then(Commands.literal("invite").then(player().then(Commands.literal("permanent").executes(c -> invite(c, Invitation.Kind.PERMANENT, 0, "")).then(perms(Invitation.Kind.PERMANENT, 0)))
            .then(Commands.literal("single").executes(c -> invite(c, Invitation.Kind.SINGLE_VISIT, 0, "")).then(perms(Invitation.Kind.SINGLE_VISIT, 0)))
            .then(Commands.literal("timed").then(Commands.argument("hours", IntegerArgumentType.integer(1)).executes(c -> invite(c, Invitation.Kind.TIMED, IntegerArgumentType.getInteger(c, "hours"), ""))
                .then(perms(Invitation.Kind.TIMED, -1))))));
        root.then(Commands.literal("permissions").then(player().then(Commands.argument("list", StringArgumentType.string()).executes(c -> run(c, owner -> {
            owner.sendSystemMessage(ArchiveService.setPermissions(owner, guest(c, owner), parse(StringArgumentType.getString(c, "list"))));
        })))));
        root.then(Commands.literal("remove").then(player().executes(c -> run(c, owner -> {
            owner.sendSystemMessage(ArchiveService.remove(owner, guest(c, owner)));
        }))));
        root.then(Commands.literal("guests").executes(c -> run(c, owner -> {
            Archive archive = ArchiveService.ownArchive(owner);
            if (archive.invitations().isEmpty()) owner.sendSystemMessage(Msg.ok("command.nullspace.guests.none"));
            var cache = owner.getServer().getProfileCache();
            archive.invitations().forEach((id, inv) -> owner.sendSystemMessage(Msg.ok(archive.activeVisits().containsKey(id) ? "command.nullspace.guests.entry_inside" : "command.nullspace.guests.entry",
                TerminalService.guestName(cache, id),
                Component.translatable("gui.nullspace.kind." + inv.kind().ordinal()), permissionNames(inv.permissions()))));
        })));
        root.then(Commands.literal("team")
            .then(Commands.literal("on").executes(c -> run(c, owner -> owner.sendSystemMessage(ArchiveService.setTeamAccess(owner, true)))))
            .then(Commands.literal("off").executes(c -> run(c, owner -> owner.sendSystemMessage(ArchiveService.setTeamAccess(owner, false)))))
            .then(Commands.literal("set").then(Commands.argument("permissions", StringArgumentType.string()).executes(c -> run(c, owner ->
                owner.sendSystemMessage(ArchiveService.setTeamPermissions(owner, parse(StringArgumentType.getString(c, "permissions"))))))))
            .then(Commands.literal("status").executes(c -> run(c, owner -> ArchiveService.teamStatusLines(owner).forEach(owner::sendSystemMessage))))
            .executes(c -> run(c, owner -> ArchiveService.teamStatusLines(owner).forEach(owner::sendSystemMessage))));
        root.then(Commands.literal("card")
            .then(Commands.literal("list").executes(c -> run(c, owner -> ArchiveService.cardLines(owner).forEach(owner::sendSystemMessage))))
            .then(Commands.literal("revoke").then(Commands.argument("number", IntegerArgumentType.integer(1)).executes(c -> run(c, owner ->
                owner.sendSystemMessage(ArchiveService.revokeCard(owner, IntegerArgumentType.getInteger(c, "number")))))))
            .then(Commands.literal("clear").then(Commands.argument("number", IntegerArgumentType.integer(1)).executes(c -> run(c, owner ->
                owner.sendSystemMessage(ArchiveService.forgetCard(owner, IntegerArgumentType.getInteger(c, "number"))))))));
        root.then(Commands.literal("preset")
            .then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.string()).then(Commands.argument("list", StringArgumentType.string()).executes(c -> run(c, owner -> {
                owner.sendSystemMessage(ArchiveService.savePreset(owner, StringArgumentType.getString(c, "name"), parse(StringArgumentType.getString(c, "list"))));
            })))))
            .then(Commands.literal("list").executes(c -> run(c, owner -> {
                var presets = ArchiveService.ownArchive(owner).presets();
                if (presets.isEmpty()) owner.sendSystemMessage(Msg.ok("command.nullspace.preset.none"));
                presets.forEach((n, p) -> owner.sendSystemMessage(Msg.ok("command.nullspace.preset.entry", n, permissionNames(p))));
            }))));
        root.then(Commands.literal("claim").then(Commands.argument("preset", StringArgumentType.string()).executes(c -> run(c, owner ->
            ArchiveService.claim(owner, StringArgumentType.getString(c, "preset"))))));
        root.then(Commands.literal("settings").then(Commands.argument("key", StringArgumentType.word()).then(Commands.argument("value", BoolArgumentType.bool()).executes(c -> run(c, owner ->
            owner.sendSystemMessage(ArchiveService.setSetting(owner, StringArgumentType.getString(c, "key").toLowerCase(Locale.ROOT), BoolArgumentType.getBool(c, "value"))))))));
        root.then(Commands.literal("room")
            .then(Commands.literal("list").executes(c -> run(c, owner -> ArchiveService.ownArchive(owner).rooms().values().forEach(r -> owner.sendSystemMessage(
                Msg.ok("command.nullspace.room.entry", r.id(), r.width(), r.height(), doorNames(r)))))))
            .then(Commands.literal("preview")
                .then(Commands.literal("expand").then(Commands.argument("room", IntegerArgumentType.integer(0)).executes(c -> run(c, owner ->
                    owner.sendSystemMessage(RoomOperations.previewExpansion(owner, IntegerArgumentType.getInteger(c, "room")).text())))))
                .then(Commands.literal("raise").then(Commands.argument("room", IntegerArgumentType.integer(0)).executes(c -> run(c, owner ->
                    owner.sendSystemMessage(RoomOperations.previewExpansion(owner, IntegerArgumentType.getInteger(c, "room"), LayoutPlanner.Axis.HEIGHT).text())))))
                .then(Commands.literal("annex").then(Commands.argument("room", IntegerArgumentType.integer(0)).then(Commands.argument("direction", StringArgumentType.word()).executes(c -> run(c, owner ->
                    owner.sendSystemMessage(RoomOperations.previewAnnex(owner, IntegerArgumentType.getInteger(c, "room"), direction(c)).text())))))))
            .then(Commands.literal("expand").then(Commands.argument("room", IntegerArgumentType.integer(0)).executes(c -> run(c, owner -> {
                owner.sendSystemMessage(RoomOperations.expand(owner, IntegerArgumentType.getInteger(c, "room")));
            }))))
            .then(Commands.literal("raise").then(Commands.argument("room", IntegerArgumentType.integer(0)).executes(c -> run(c, owner -> {
                owner.sendSystemMessage(RoomOperations.expand(owner, IntegerArgumentType.getInteger(c, "room"), LayoutPlanner.Axis.HEIGHT));
            }))))
            .then(Commands.literal("annex").then(Commands.argument("room", IntegerArgumentType.integer(0)).then(Commands.argument("direction", StringArgumentType.word()).executes(c -> run(c, owner -> {
                owner.sendSystemMessage(RoomOperations.annex(owner, IntegerArgumentType.getInteger(c, "room"), direction(c)));
            }))))));
        root.then(Commands.literal("window").executes(c -> run(c, owner -> owner.sendSystemMessage(RoomOperations.enlargeStarterWindow(owner)))));
        root.then(Commands.literal("destination")
            .then(Commands.literal("here").then(Commands.argument("name", StringArgumentType.string()).executes(c -> run(c, owner ->
                owner.sendSystemMessage(HubDestinations.captureHere(owner, StringArgumentType.getString(c, "name")))))))
            .then(Commands.literal("list").executes(c -> run(c, owner -> {
                var basement = ArchiveService.ownArchive(owner).basement();
                if (basement.destinations().isEmpty()) owner.sendSystemMessage(Msg.ok("command.nullspace.destination.none"));
                basement.destinations().values().forEach(d -> owner.sendSystemMessage(Msg.ok("command.nullspace.destination.entry", d.name(), d.dimension(),
                    (int) Math.floor(d.x()), (int) Math.floor(d.y()), (int) Math.floor(d.z()), basement.slotOf(d.id()) < 0 ? "-" : String.valueOf(basement.slotOf(d.id()) + 1)))); // i18n-ok: pad number
            }))));
        root.then(AdminCommands.build());
        dispatcher.register(root);
    }

    // a plain name, resolved like the terminal does (TerminalService.profile). not GameProfileArgument: for an
    // unknown name it asks the profile service on the server thread, and any player can run these commands
    private static RequiredArgumentBuilder<CommandSourceStack, ?> player() {
        return Commands.argument("player", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b));
    }

    static List<ArgumentType<?>> playerArgumentTypes(CommandDispatcher<CommandSourceStack> dispatcher) {
        var found = new ArrayList<ArgumentType<?>>();
        var root = dispatcher.getRoot().getChild("nullspace");
        for (String command : new String[] {"invite", "permissions", "remove"}) {
            var node = root.getChild(command).getChild("player");
            found.add(((ArgumentCommandNode<?, ?>) node).getType());
        }
        return found;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> perms(Invitation.Kind kind, int hours) {
        return Commands.argument("permissions", StringArgumentType.string()).executes(c -> invite(c, kind, hours < 0 ? IntegerArgumentType.getInteger(c, "hours") : hours, StringArgumentType.getString(c, "permissions")));
    }

    private static Direction direction(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "direction");
        try { return Direction.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new ArchiveException("error.nullspace.command.bad_direction", name); }
    }

    private static GameProfile profile(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return TerminalService.profile(c.getSource().getServer(), StringArgumentType.getString(c, "player"));
    }

    private static GameProfile guest(CommandContext<CommandSourceStack> c, ServerPlayer owner) {
        return TerminalService.guest(c.getSource().getServer(), ArchiveService.ownArchive(owner), StringArgumentType.getString(c, "player"));
    }

    private static Set<Permission> resolve(ServerPlayer owner, String spec) {
        if (spec.isEmpty()) return Invitation.observerPermissions();
        Set<Permission> preset = ArchiveService.ownArchive(owner).presets().get(spec);
        return preset != null ? preset : parse(spec);
    }

    private static Set<Permission> parse(String list) {
        Set<Permission> result = EnumSet.of(Permission.ENTER);
        for (String part : list.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            try { result.add(Permission.valueOf(name)); }
            catch (IllegalArgumentException e) { throw new ArchiveException("error.nullspace.command.bad_permission", part.trim()); }
        }
        return result;
    }

    private static int invite(CommandContext<CommandSourceStack> c, Invitation.Kind kind, int hours, String spec) {
        return run(c, owner -> {
            owner.sendSystemMessage(ArchiveService.invite(owner, profile(c), kind, hours, resolve(owner, spec)));
        });
    }

    private static int run(CommandContext<CommandSourceStack> c, Action action) {
        try {
            action.run(c.getSource().getPlayerOrException());
            return 1;
        } catch (IllegalArgumentException | SecurityException | CommandSyntaxException e) {
            c.getSource().sendFailure(Msg.of(e));
        }
        return 0;
    }

    private static Component permissionNames(Set<Permission> permissions) {
        var names = new ArrayList<Component>();
        for (Permission p : Permission.values()) if (permissions.contains(p)) names.add(Component.translatable(Msg.permissionKey(p)));
        return ComponentUtils.formatList(names, Component.literal(", ")); // i18n-ok: list separator
    }

    private static Component doorNames(Room room) {
        var names = new ArrayList<Component>();
        for (var d : room.connections().keySet()) names.add(Component.translatable("gui.nullspace.dir." + d.name().toLowerCase(Locale.ROOT)));
        return names.isEmpty() ? Component.translatable("command.nullspace.room.no_doors") : ComponentUtils.formatList(names, Component.literal(", ")); // i18n-ok: list separator
    }
}
