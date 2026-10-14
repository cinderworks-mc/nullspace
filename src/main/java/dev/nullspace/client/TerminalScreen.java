package dev.nullspace.client;

import dev.nullspace.client.Console.Btn;
import dev.nullspace.client.Console.Field;
import dev.nullspace.core.Direction;
import dev.nullspace.core.Permission;
import dev.nullspace.core.Room;
import dev.nullspace.network.TerminalAction;
import dev.nullspace.network.TerminalSnapshot;
import dev.nullspace.server.Finishes;
import dev.nullspace.server.HubDestinations;
import dev.nullspace.text.Msg;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static dev.nullspace.client.Console.*;
import static dev.nullspace.client.TerminalLayout.*;

// warm retro console, only a view: every button sends a request the server checks again. fixed 300x226 panel (fits
// minecraft's 320x240 minimum virtual screen at every gui scale); long lists page
public final class TerminalScreen extends Screen {
    static final int W = TerminalLayout.W, H = TerminalLayout.H;
    private static final int GUEST_ROWS = 4, ROW_H = 20, ROOM_ROWS = 9, ROOM_ROW_H = 15, DIALOG_LINES = 9;
    static final Permission[] GUEST_PERMS = {Permission.ENTER, Permission.STORAGE, Permission.INTERACT_BLOCK, Permission.BUILD, Permission.INTERACT_ENTITY, Permission.SET_SPAWN};
    private static final String[] ICONS = {"permission_enter", "permission_storage", "permission_craft", "permission_build", "permission_entity", "permission_spawn"};
    private static final String[] KINDS = {"gui.nullspace.kind.0", "gui.nullspace.kind.1", "gui.nullspace.kind.2"};
    private static final int[] HOURS = {1, 6, 24, 72, 168, 720};
    private static Component hourLabel(int i) {
        int h = HOURS[i];
        return h % 24 != 0 ? Component.translatable("gui.nullspace.duration.hours", h)
            : h == 24 ? Component.translatable("gui.nullspace.duration.day") : Component.translatable("gui.nullspace.duration.days", h / 24);
    }
    private static final String[] DIRS = {"gui.nullspace.dir.north", "gui.nullspace.dir.east", "gui.nullspace.dir.south", "gui.nullspace.dir.west"};
    private static final String[] SURFACES = {"floor", "ceiling", "north", "east", "south", "west"};
    private static Component surfaceName(int s) { return Component.translatable("gui.nullspace.surface." + SURFACES[s]); }
    private static Component surfaceLong(int s) { return Component.translatable("gui.nullspace.surface.long." + SURFACES[s]); }
    private static final String[] TABS = {"gui.nullspace.tab.rooms", "gui.nullspace.tab.guests", "gui.nullspace.tab.settings", "gui.nullspace.tab.destinations"};
    private static final String[] TAB_ICONS = {"room", "permission_enter", "upgrade", "destinations"};
    private int[] tabX = new int[4], tabW = new int[4];
    private static final int DEST_ROWS = 6, DEST_ROW_H = 15;
    private static final String PRESET_PATTERN = "[A-Za-z0-9 _.-]{1,48}";

    private TerminalSnapshot data;
    private int tab, selectedRoom, roomScroll, guestScroll, previewScroll, paintSurface = -1, selectedDest = -1, destScroll;
    private String destName = "";
    private int deleteArmed = -1;
    private EditBox destBox;
    private int kind, hoursIdx, presetIdx, inviteMask = 1 << Permission.ENTER.ordinal();
    private boolean cardMode;
    private int cardScroll, cardKind, cardHoursIdx, cardPresetIdx, cardMask = 1 << Permission.ENTER.ordinal(), cardArmed = -1;
    private boolean cardHolder;
    private Btn cardPrintButton;
    private String inviteName = "", presetName = "";
    private UUID removeArmed;
    private TerminalAction pending;
    private EditBox nameBox, presetBox;
    private Btn inviteButton, presetSaveButton;
    private int left, top;
    // 1 unless the window is smaller than the panel (below minecraft's 320x240 guarantee), then everything is drawn
    // and clicked scaled down
    private float scale = 1f;

    TerminalScreen(TerminalSnapshot data) { super(Component.translatable("gui.nullspace.title")); this.data = data; }

    void update(TerminalSnapshot next) {
        this.data = next;
        if (!next.canConfirm()) { pending = null; previewScroll = 0; }
        removeArmed = null;
        cardArmed = -1;
        // keep what was typed when the server refused it, so a typo can be corrected instead of retyped.
        if (!next.failed() && lastSent == TerminalAction.Kind.INVITE) inviteName = "";
        if (!next.failed() && lastSent == TerminalAction.Kind.SAVE_PRESET) presetName = "";
        if (!next.failed() && (lastSent == TerminalAction.Kind.DEST_CAPTURE || lastSent == TerminalAction.Kind.DEST_IMPORT || lastSent == TerminalAction.Kind.DEST_RENAME)) destName = "";
        deleteArmed = -1;
        lastSent = null;
        rebuildWidgets();
    }

    private TerminalAction.Kind lastSent;

    void send(TerminalAction.Kind kind, String text, int a, int b, int c) {
        lastSent = kind;
        PacketDistributor.sendToServer(new TerminalAction(kind, text, a, b, c));
    }

    private boolean dialogOpen() { return tab == 0 && pending != null && data.canConfirm() && !data.preview().getString().isEmpty(); }

    private static Component hint(String key, int width) { return Component.literal(clip(Component.translatable(key).getString(), width)).withStyle(st -> st.withColor(TextColor.fromRgb(FAINT & 0xFFFFFF))); }

    private void label(GuiGraphics g, String full, int width, int x, int y, int colour) {
        String shown = clip(full, width);
        g.drawString(font, shown, x, y, colour, false);
        if (!shown.equals(full)) clipped.add(new Clipped(x, y - 1, width, full));
    }

    private record Clipped(int x, int y, int w, String full) {}
    private final List<Clipped> clipped = new ArrayList<>();

    private Btn add(Btn b) { addRenderableWidget(b); return b; }

    @Override protected void init() {
        scale = Math.min(1f, Math.min(width / (W + 4f), height / (H + 4f)));
        int vw = Math.round(width / scale), vh = Math.round(height / scale);
        left = (vw - W) / 2; top = Math.max(2, (vh - H) / 2);
        String[] labels = new String[TABS.length];
        for (int i = 0; i < TABS.length; i++) labels[i] = Component.translatable(TABS[i]).getString();
        int[][] tabs = TerminalLayout.tabs(font::width, labels);
        tabX = tabs[0]; tabW = tabs[1];
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            add(new Btn("tab:" + i, left + tabX[i], top + TAB_Y, tabW[i], TAB_H, Component.translatable(TABS[i]), () -> { tab = index; pending = null; removeArmed = null; rebuildWidgets(); if (index != 0 || data.canConfirm()) send(TerminalAction.Kind.REFRESH, "", 0, 0, 0); })).pad(TAB_ICON_PAD - 3, 0).selected(tab == i);
        }
        add(new Btn("close", left + CLOSE_X, top + 5, CLOSE_W, 12, Component.literal("x"), this::onClose).style(Btn.Style.DANGER));
        switch (tab) { case 0 -> rooms(); case 1 -> guests(); case 2 -> settings(); default -> destinations(); }
    }


    private TerminalSnapshot.RoomInfo room() {
        for (var r : data.rooms()) if (r.id() == selectedRoom) return r;
        return data.rooms().isEmpty() ? null : data.rooms().get(0);
    }

    private void rooms() {
        List<TerminalSnapshot.RoomInfo> rooms = data.rooms();
        if (rooms.isEmpty()) return;
        var room = room();
        selectedRoom = room.id();
        roomScroll = Math.max(0, Math.min(roomScroll, rooms.size() - ROOM_ROWS));
        for (int i = 0; i < ROOM_ROWS && roomScroll + i < rooms.size(); i++) {
            var r = rooms.get(roomScroll + i);
            add(new Btn("room:" + r.id(), left + 10, top + 58 + i * ROOM_ROW_H, 94, ROOM_ROW_H - 1, Component.translatable("gui.nullspace.room.row", r.id() == 0 ? Component.translatable("gui.nullspace.room.start") : Component.translatable("gui.nullspace.room.n", r.id()), r.width(), r.height()),
                () -> { selectedRoom = r.id(); pending = null; paintSurface = -1; send(TerminalAction.Kind.REFRESH, "", 0, 0, 0); })).selected(r.id() == selectedRoom).leftAligned();
        }
        if (rooms.size() > ROOM_ROWS) {
            add(new Btn("rooms-up", left + 74, top + 45, 14, 11, Component.literal("^"), () -> { roomScroll--; rebuildWidgets(); })).enabled(roomScroll > 0);
            add(new Btn("rooms-down", left + 90, top + 45, 14, 11, Component.literal("v"), () -> { roomScroll++; rebuildWidgets(); })).enabled(roomScroll + ROOM_ROWS < rooms.size());
        }
        int x = left + 112;
        if (dialogOpen()) {
            add(new Btn("confirm", x, top + 174, 108, 18, Component.translatable("gui.nullspace.confirm"), () -> {
                send(pending.kind(), "", pending.a(), pending.b(), 0); pending = null; }).style(Btn.Style.ACCENT));
            add(new Btn("cancel", x + 112, top + 174, 66, 18, Component.translatable("gui.nullspace.cancel"), () -> { pending = null; previewScroll = 0; send(TerminalAction.Kind.REFRESH, "", 0, 0, 0); }));
            return;
        }
        int nextWide = Room.nextWidth(room.id(), room.width()), nextHigh = Room.nextHeight(room.height());
        boolean wideMax = nextWide < 0, wideVeto = !wideMax && nextWide > data.maxRoomWidth();
        boolean highMax = nextHigh < 0, highVeto = !highMax && nextHigh > data.maxCeilingHeight();
        Btn widen = add(new Btn("enlarge", x, top + 58, 88, 16, wideMax ? Component.translatable("gui.nullspace.widen.max") : Component.translatable("gui.nullspace.widen", nextWide), () -> {
            pending = new TerminalAction(TerminalAction.Kind.EXPAND, "", room.id(), 0, 0);
            send(TerminalAction.Kind.PREVIEW_EXPAND, "", room.id(), 0, 0);
        })).style(Btn.Style.ACCENT).enabled(!wideMax && !wideVeto);
        if (wideMax) widen.setTooltip(Tooltip.create(Component.translatable(room.id() == Room.STARTER_ID ? "gui.nullspace.widen.max.tip_hall" : "gui.nullspace.widen.max.tip", Room.MAX_ROOM_WIDTH)));
        else if (wideVeto) widen.setTooltip(Tooltip.create(Component.translatable("gui.nullspace.widen.veto", data.maxRoomWidth())));
        else if (nextWide > Room.MAX_ROOM_WIDTH) widen.setTooltip(Tooltip.create(Component.translatable("gui.nullspace.widen.hall")));
        Btn raise = add(new Btn("raise", x + 90, top + 58, 88, 16, highMax ? Component.translatable("gui.nullspace.raise.max") : Component.translatable("gui.nullspace.raise", nextHigh), () -> {
            pending = new TerminalAction(TerminalAction.Kind.EXPAND, "", room.id(), 1, 0);
            send(TerminalAction.Kind.PREVIEW_EXPAND, "", room.id(), 1, 0);
        })).style(Btn.Style.ACCENT).enabled(!highMax && !highVeto);
        if (highMax) raise.setTooltip(Tooltip.create(Component.translatable("gui.nullspace.raise.max.tip")));
        else if (highVeto) raise.setTooltip(Tooltip.create(Component.translatable("gui.nullspace.raise.veto", data.maxCeilingHeight())));
        boolean canAdd = data.rooms().size() < data.maxRooms();
        for (int d = 0; d < 4; d++) {
            int dir = d;
            boolean door = (room.doors() & (1 << d)) != 0;
            Btn b = add(new Btn("annex:" + d, x + (d % 2) * 90, top + 80 + (d / 2) * 16, 88, 15, Component.translatable(door ? "gui.nullspace.annex.joined" : "gui.nullspace.annex.add", Component.translatable(DIRS[d])), () -> {
                pending = new TerminalAction(TerminalAction.Kind.ANNEX, "", room.id(), dir, 0);
                send(TerminalAction.Kind.PREVIEW_ANNEX, "", room.id(), dir, 0);
            })).enabled(!door && canAdd);
            if (!door && !canAdd) b.setTooltip(Tooltip.create(Component.translatable("gui.nullspace.annex.limit", data.maxRooms())));
        }
        for (int s = 0; s < 6 && s < room.finishes().size(); s++) {
            int surface = s;
            add(new Btn("surf:" + s, x + (s % 3) * 60, top + 128 + (s / 3) * 16, 58, 15, surfaceName(s), () -> { paintSurface = paintSurface == surface ? -1 : surface; rebuildWidgets(); }))
                .selected(paintSurface == s).swatch(room.finishes().get(s)).leftAligned()
                .setTooltip(Tooltip.create(Component.translatable("gui.nullspace.surface.tip", surfaceLong(s), Finishes.name(room.finishes().get(s)))));
        }
        if (paintSurface >= 0 && paintSurface < room.finishes().size()) {
            List<String> all = Finishes.all();
            for (int i = 0; i < all.size(); i++) {
                String id = all.get(i);
                int surface = paintSurface;
                add(new Btn("finish:" + id.substring(id.indexOf(':') + 1), x + i * 25, top + 172, 23, 22, Component.empty(), () -> send(TerminalAction.Kind.SURFACE, id, room.id(), surface, 0)))
                    .selected(id.equals(room.finishes().get(paintSurface))).swatch(id)
                    .setTooltip(Tooltip.create(finishTip(id, false)));
            }
        }
    }

    static Component finishTip(String id, boolean selected) {
        var tip = Finishes.name(id).copy();
        if (id.equals(Finishes.WINDOW)) tip.append(Component.translatable("gui.nullspace.finish.window_note"));
        if (selected) tip.append(Component.translatable("gui.nullspace.finish.selected_note"));
        return tip;
    }


    private boolean allowed(int p) { return (data.allowedPerms() & (1 << GUEST_PERMS[p].ordinal())) != 0 || GUEST_PERMS[p] == Permission.ENTER; }

    private Tooltip permTip(int p, boolean on, boolean forbidden) {
        String key = "nullspace.perm." + GUEST_PERMS[p].name().toLowerCase(Locale.ROOT);
        Component state = forbidden ? Component.translatable("nullspace.perm.forbidden") : Component.translatable(on ? "gui.nullspace.on" : "gui.nullspace.off");
        return Tooltip.create(Component.translatable(key).append(Component.translatable("gui.nullspace.perm.tip.separator")).append(state).append(Component.translatable("gui.nullspace.perm.tip.newline")).append(Component.translatable(key + ".desc")));
    }

    private int teamRows() { return data.team().present() ? 1 : 0; }
    private int guestRows() { return GUEST_ROWS - teamRows(); }

    private void teamRow() {
        var team = data.team();
        int y = top + 58;
        boolean paused = team.on() && team.status() != 2;
        for (int p = 0; p < GUEST_PERMS.length; p++) {
            int bit = 1 << GUEST_PERMS[p].ordinal(), pi = p;
            boolean on = (team.permissions() & bit) != 0, forbidden = !allowed(p);
            Btn t = add(new Btn("teamperm:" + GUEST_PERMS[p].name(), left + PERM_X + p * PERM_STEP, y + 1, PERM_W, 18, Component.empty(), () -> {
                if (GUEST_PERMS[pi] == Permission.ENTER || forbidden) return;
                send(TerminalAction.Kind.TEAM_PERMISSIONS, "", team.permissions() ^ bit, 0, 0);
            })).icon(ICONS[p], on || GUEST_PERMS[p] == Permission.ENTER);
            t.locked = GUEST_PERMS[p] == Permission.ENTER;
            t.forbidden = forbidden;
            t.setTooltip(permTip(p, on || t.locked, forbidden));
        }
        Component label = paused ? Component.translatable("gui.nullspace.team.renew") : Component.translatable(team.on() ? "gui.nullspace.on" : "gui.nullspace.off");
        add(new Btn("team-toggle", left + REMOVE_X, y + 1, REMOVE_W, 18, label, () -> send(TerminalAction.Kind.TEAM_TOGGLE, "", paused || !team.on() ? 1 : 0, 0, 0)))
            .style(team.on() && !paused ? Btn.Style.ACCENT : Btn.Style.NORMAL)
            .setTooltip(Tooltip.create(Component.translatable(paused ? "gui.nullspace.team.tip.renew" : team.on() ? "gui.nullspace.team.tip.on" : "gui.nullspace.team.tip.off")));
    }

    private void modeButtons() {
        add(new Btn("mode:guests", left + MODE_GUESTS_X, top + MODE_Y, MODE_GUESTS_W, MODE_H, Component.translatable("gui.nullspace.guests.count", data.guests().size(), data.maxGuests()), () -> { cardMode = false; cardArmed = -1; rebuildWidgets(); }))
            .selected(!cardMode).setTooltip(Tooltip.create(Component.translatable("gui.nullspace.mode.tip.guests")));
        add(new Btn("mode:cards", left + MODE_CARDS_X, top + MODE_Y, MODE_CARDS_W, MODE_H, Component.translatable("gui.nullspace.cards.count", data.cardSettings().live(), data.cardSettings().maxLive()), () -> { cardMode = true; removeArmed = null; rebuildWidgets(); }))
            .selected(cardMode).setTooltip(Tooltip.create(Component.translatable("gui.nullspace.mode.tip.cards")));
    }

    private static Component cardKindName(int kind) { return Component.translatable("gui.nullspace.card.kind." + Math.floorMod(kind, 3)); }

    private int[] cardHours() {
        int max = Math.max(1, data.cardSettings().maxHours()), n = 0;
        int[] all = new int[HOURS.length];
        for (int h : HOURS) if (h <= max) all[n++] = h;
        return n == 0 ? new int[] {1} : Arrays.copyOf(all, n);
    }

    private Component cardBlocker() {
        var cs = data.cardSettings();
        if (!cs.enabled()) return Msg.disabledText("cards");
        if (cs.blank() <= 0) return Component.translatable("gui.nullspace.card.block.blank");
        if (cs.live() >= cs.maxLive()) return Component.translatable("gui.nullspace.card.block.limit", cs.live(), cs.maxLive());
        return null;
    }

    private void printCard() {
        if (cardBlocker() != null) return;
        int[] hours = cardHours();
        send(TerminalAction.Kind.PRINT_CARD, "", cardMask, cardKind | (cardHolder ? 4 : 0), cardKind == 1 ? hours[Math.min(cardHoursIdx, hours.length - 1)] : 0);
    }

    private void cards() {
        nameBox = null; presetBox = null; inviteButton = null; presetSaveButton = null;
        List<TerminalSnapshot.CardInfo> list = data.cards();
        final int rows = GUEST_ROWS;
        cardScroll = Math.max(0, Math.min(cardScroll, Math.max(0, list.size() - rows)));
        if (list.size() > rows) {
            add(new Btn("cards-prev", left + 252, top + 44, 16, 12, Component.literal("<"), () -> { cardScroll = Math.max(0, cardScroll - rows); rebuildWidgets(); })).enabled(cardScroll > 0);
            add(new Btn("cards-next", left + 272, top + 44, 16, 12, Component.literal(">"), () -> { cardScroll = Math.min(list.size() - rows, cardScroll + rows); rebuildWidgets(); })).enabled(cardScroll + rows < list.size());
        }
        for (int i = 0; i < rows && cardScroll + i < list.size(); i++) {
            var c = list.get(cardScroll + i);
            int y = top + 58 + i * ROW_H;
            for (int p = 0; p < GUEST_PERMS.length; p++) {
                int bit = 1 << GUEST_PERMS[p].ordinal();
                boolean on = (c.permissions() & bit) != 0;
                Btn t = add(new Btn("cardperm:" + c.number() + ":" + GUEST_PERMS[p].name(), left + PERM_X + p * PERM_STEP, y + 1, PERM_W, 18, Component.empty(), () -> { })).icon(ICONS[p], on || GUEST_PERMS[p] == Permission.ENTER);
                t.locked = GUEST_PERMS[p] == Permission.ENTER;
                t.setTooltip(permTip(p, on || t.locked, false));
            }
            if (c.state() == 0) {
                boolean armed = cardArmed == c.number();
                add(new Btn("card-revoke:" + c.number(), left + REMOVE_X, y + 1, REMOVE_W, 18, Component.translatable(armed ? "gui.nullspace.card.revoke.sure" : "gui.nullspace.card.revoke"), () -> {
                    if (cardArmed == c.number()) { cardArmed = -1; send(TerminalAction.Kind.REVOKE_CARD, "", c.number(), 0, 0); }
                    else { cardArmed = c.number(); rebuildWidgets(); }
                })).style(armed ? Btn.Style.DANGER : Btn.Style.NORMAL).setTooltip(Tooltip.create(Component.translatable("gui.nullspace.card.revoke.tip." + (armed ? "armed" : "idle"))));
            } else {
                add(new Btn("card-forget:" + c.number(), left + REMOVE_X, y + 1, REMOVE_W, 18, Component.translatable("gui.nullspace.card.forget"), () -> send(TerminalAction.Kind.FORGET_CARD, "", c.number(), 0, 0)))
                    .setTooltip(Tooltip.create(Component.translatable("gui.nullspace.card.forget.tip")));
            }
        }
        int iy = top + 142;
        boolean timed = cardKind == 1;
        add(new Btn("card-kind", left + CARD_KIND_X, iy, CARD_KIND_W, 16, cardKindName(cardKind), () -> { cardKind = (cardKind + 1) % 3; rebuildWidgets(); }))
            .setTooltip(Tooltip.create(Component.translatable("gui.nullspace.card.kind.tip")));
        int[] hours = cardHours();
        cardHoursIdx = Math.min(cardHoursIdx, hours.length - 1);
        if (timed) add(new Btn("card-hours", left + CARD_HOURS_X, iy, CARD_HOURS_W, 16, hourLabelOf(hours[cardHoursIdx]), () -> { cardHoursIdx = (cardHoursIdx + 1) % hours.length; rebuildWidgets(); }))
            .setTooltip(Tooltip.create(Component.translatable("gui.nullspace.card.hours.tip")));
        add(new Btn("card-bind", left + cardBindX(timed), iy, cardBindW(timed), 16, Component.translatable(cardHolder ? "gui.nullspace.card.bind.holder" : "gui.nullspace.card.bind.first"), () -> { cardHolder = !cardHolder; rebuildWidgets(); }))
            .setTooltip(Tooltip.create(Component.translatable(cardHolder ? "gui.nullspace.card.bind.tip.holder" : "gui.nullspace.card.bind.tip.first")));
        cardPrintButton = add(new Btn("card-print", left + CARD_PRINT_X, iy, CARD_PRINT_W, 16, Component.translatable("gui.nullspace.card.print"), this::printCard).style(Btn.Style.ACCENT));
        Component why = cardBlocker();
        cardPrintButton.active = why == null;
        cardPrintButton.setTooltip(Tooltip.create(why == null ? Component.translatable("gui.nullspace.card.print.tip") : why));
        int allowedBits = 0;
        for (int p = 0; p < GUEST_PERMS.length; p++) if (allowed(p)) allowedBits |= 1 << GUEST_PERMS[p].ordinal();
        final int allowedMask = allowedBits;
        cardMask = (cardMask & allowedMask) | 1 << Permission.ENTER.ordinal();
        for (int p = 0; p < GUEST_PERMS.length; p++) {
            int bit = 1 << GUEST_PERMS[p].ordinal(), pi = p;
            boolean on = (cardMask & bit) != 0, forbidden = !allowed(p);
            Btn t = add(new Btn("newcardperm:" + GUEST_PERMS[p].name(), left + MARGIN + p * PERM_STEP, iy + 20, PERM_W, 18, Component.empty(), () -> {
                if (GUEST_PERMS[pi] == Permission.ENTER || forbidden) return;
                cardMask ^= bit; cardPresetIdx = 0; rebuildWidgets();
            })).icon(ICONS[p], on);
            t.locked = GUEST_PERMS[p] == Permission.ENTER;
            t.forbidden = forbidden;
            t.setTooltip(permTip(p, on, forbidden));
        }
        List<TerminalSnapshot.PresetInfo> presets = data.presets();
        if (cardPresetIdx > presets.size()) cardPresetIdx = 0;
        add(new Btn("card-preset", left + PRESET_X, iy + 20, PRESET_W, 18, Component.translatable("gui.nullspace.preset", cardPresetIdx == 0 ? Component.translatable("gui.nullspace.preset.custom") : Component.literal(presets.get(cardPresetIdx - 1).name())), () -> { // i18n-ok: player-chosen name
            cardPresetIdx = (cardPresetIdx + 1) % (presets.size() + 1);
            if (cardPresetIdx > 0) cardMask = (presets.get(cardPresetIdx - 1).mask() & allowedMask) | 1 << Permission.ENTER.ordinal();
            rebuildWidgets();
        })).leftAligned().setTooltip(Tooltip.create(cardPresetIdx == 0 ? Component.translatable(presets.isEmpty() ? "gui.nullspace.preset.tip.none" : "gui.nullspace.preset.tip")
            : Component.literal(presets.get(cardPresetIdx - 1).name()).append("\n").append(Component.translatable("gui.nullspace.preset.tip")))); // i18n-ok: player-chosen name, shown in full here
    }

    private static Component hourLabelOf(int h) {
        return h % 24 != 0 ? Component.translatable("gui.nullspace.duration.hours", h)
            : h == 24 ? Component.translatable("gui.nullspace.duration.day") : Component.translatable("gui.nullspace.duration.days", h / 24);
    }

    private void guests() {
        modeButtons();
        if (cardMode) { cards(); return; }
        List<TerminalSnapshot.GuestInfo> guests = data.guests();
        final int rows = guestRows(), off = teamRows();
        guestScroll = Math.max(0, Math.min(guestScroll, Math.max(0, guests.size() - rows)));
        if (guests.size() > rows) {
            add(new Btn("guests-prev", left + 252, top + 44, 16, 12, Component.literal("<"), () -> { guestScroll = Math.max(0, guestScroll - rows); rebuildWidgets(); })).enabled(guestScroll > 0);
            add(new Btn("guests-next", left + 272, top + 44, 16, 12, Component.literal(">"), () -> { guestScroll = Math.min(guests.size() - rows, guestScroll + rows); rebuildWidgets(); })).enabled(guestScroll + rows < guests.size());
        }
        if (off > 0) teamRow();
        for (int i = 0; i < rows && guestScroll + i < guests.size(); i++) {
            var g = guests.get(guestScroll + i);
            int y = top + 58 + (i + off) * ROW_H;
            for (int p = 0; p < GUEST_PERMS.length; p++) {
                int bit = 1 << GUEST_PERMS[p].ordinal(), pi = p;
                boolean on = (g.permissions() & bit) != 0, forbidden = !allowed(p);
                Btn t = add(new Btn("perm:" + g.name() + ":" + GUEST_PERMS[p].name(), left + PERM_X + p * PERM_STEP, y + 1, PERM_W, 18, Component.empty(), () -> {
                    if (GUEST_PERMS[pi] == Permission.ENTER || forbidden) return;
                    send(TerminalAction.Kind.SET_PERMISSIONS, g.name(), g.permissions() ^ bit, 0, 0);
                })).icon(ICONS[p], on || GUEST_PERMS[p] == Permission.ENTER);
                t.locked = GUEST_PERMS[p] == Permission.ENTER;
                t.forbidden = forbidden;
                t.setTooltip(permTip(p, on || t.locked, forbidden));
            }
            boolean armed = g.id().equals(removeArmed);
            add(new Btn("remove:" + g.name(), left + REMOVE_X, y + 1, REMOVE_W, 18, Component.translatable(armed ? "gui.nullspace.remove.sure" : "gui.nullspace.remove"), () -> {
                if (g.id().equals(removeArmed)) { removeArmed = null; send(TerminalAction.Kind.REMOVE, g.name(), 0, 0, 0); }
                else { removeArmed = g.id(); rebuildWidgets(); }
            })).style(armed ? Btn.Style.DANGER : Btn.Style.NORMAL).setTooltip(Tooltip.create(Component.translatable("gui.nullspace.remove.tip." + (armed ? "armed" : "idle") + (g.inside() ? ".inside" : ""))));
        }
        int iy = top + 142;
        boolean timed = kind == 1;
        nameBox = new Field(font, left + MARGIN, iy, NAME_BOX_W, 16, Component.translatable("gui.nullspace.player_name"));
        nameBox.setMaxLength(16); nameBox.setHint(hint("gui.nullspace.player_name", NAME_BOX_W - 8)); nameBox.setValue(inviteName);
        nameBox.setResponder(s -> { inviteName = s; refreshInvite(); });
        addRenderableWidget(nameBox);
        add(new Btn("kind", left + KIND_X, iy, timed ? KIND_W_TIMED : KIND_W, 16, Component.translatable(KINDS[kind]), () -> { kind = (kind + 1) % 3; rebuildWidgets(); }))
            .setTooltip(Tooltip.create(Component.translatable("gui.nullspace.kind.tip")));
        if (timed) add(new Btn("hours", left + HOURS_X, iy, HOURS_W, 16, hourLabel(hoursIdx), () -> { hoursIdx = (hoursIdx + 1) % HOURS.length; rebuildWidgets(); }))
            .setTooltip(Tooltip.create(Component.translatable("gui.nullspace.hours.tip")));
        inviteButton = add(new Btn("invite", left + INVITE_X, iy, INVITE_W, 16, Component.translatable("gui.nullspace.invite"), this::invite).style(Btn.Style.ACCENT));
        int allowedMask = 0;
        for (int p = 0; p < GUEST_PERMS.length; p++) if (allowed(p)) allowedMask |= 1 << GUEST_PERMS[p].ordinal();
        inviteMask = (inviteMask & allowedMask) | 1 << Permission.ENTER.ordinal();
        for (int p = 0; p < GUEST_PERMS.length; p++) {
            int bit = 1 << GUEST_PERMS[p].ordinal(), pi = p;
            boolean on = (inviteMask & bit) != 0, forbidden = !allowed(p);
            Btn t = add(new Btn("newperm:" + GUEST_PERMS[p].name(), left + MARGIN + p * PERM_STEP, iy + 20, PERM_W, 18, Component.empty(), () -> {
                if (GUEST_PERMS[pi] == Permission.ENTER || forbidden) return;
                inviteMask ^= bit; presetIdx = 0; rebuildWidgets();
            })).icon(ICONS[p], on);
            t.locked = GUEST_PERMS[p] == Permission.ENTER;
            t.forbidden = forbidden;
            t.setTooltip(permTip(p, on, forbidden));
        }
        List<TerminalSnapshot.PresetInfo> presets = data.presets();
        if (presetIdx > presets.size()) presetIdx = 0;
        add(new Btn("preset-cycle", left + PRESET_X, iy + 20, PRESET_W, 18, Component.translatable("gui.nullspace.preset", presetIdx == 0 ? Component.translatable("gui.nullspace.preset.custom") : Component.literal(presets.get(presetIdx - 1).name())), () -> { // i18n-ok: player-chosen name
            presetIdx = (presetIdx + 1) % (presets.size() + 1);
            if (presetIdx > 0) inviteMask = presets.get(presetIdx - 1).mask() | 1 << Permission.ENTER.ordinal();
            rebuildWidgets();
        })).leftAligned().setTooltip(Tooltip.create(presetIdx == 0 ? Component.translatable(presets.isEmpty() ? "gui.nullspace.preset.tip.none" : "gui.nullspace.preset.tip")
            : Component.literal(presets.get(presetIdx - 1).name()).append("\n").append(Component.translatable("gui.nullspace.preset.tip")))); // i18n-ok: player-chosen name, shown in full here
        presetBox = new Field(font, left + MARGIN, iy + 42, NAME_BOX_W, 16, Component.translatable("gui.nullspace.preset_name"));
        presetBox.setMaxLength(32); presetBox.setHint(hint("gui.nullspace.preset_name.new", NAME_BOX_W - 8)); presetBox.setValue(presetName);
        presetBox.setResponder(s -> { presetName = s; refreshInvite(); });
        addRenderableWidget(presetBox);
        presetSaveButton = add(new Btn("preset-save", left + SAVE_X, iy + 42, SAVE_W, 16, Component.translatable("gui.nullspace.preset.save"), () ->
            send(TerminalAction.Kind.SAVE_PRESET, presetName.trim(), inviteMask, 0, 0)));
        refreshInvite();
    }

    private Component inviteBlocker() {
        String name = inviteName.trim();
        if (name.isEmpty()) return Component.translatable("gui.nullspace.block.name");
        if (name.equalsIgnoreCase(data.owner())) return Component.translatable("gui.nullspace.block.self");
        boolean known = data.guests().stream().anyMatch(g -> g.name().equalsIgnoreCase(name));
        if (!known && data.guests().size() >= data.maxGuests()) return Component.translatable("gui.nullspace.block.limit", data.guests().size(), data.maxGuests());
        return null;
    }

    private Component presetBlocker() {
        String name = presetName.trim();
        if (name.isEmpty()) return Component.translatable("gui.nullspace.block.preset_name");
        if (!name.matches(PRESET_PATTERN)) return Component.translatable("gui.nullspace.block.preset_chars");
        if (data.presets().size() >= 32 && data.presets().stream().noneMatch(p -> p.name().equals(name))) return Component.translatable("gui.nullspace.block.preset_limit");
        return null;
    }

    private void refreshInvite() {
        if (inviteButton == null || tab != 1 || cardMode) return;
        Component why = inviteBlocker();
        inviteButton.active = why == null;
        inviteButton.setTooltip(Tooltip.create(why == null ? Component.translatable("gui.nullspace.invite.tip", inviteName.trim()) : why));
        Component preset = presetBlocker();
        presetSaveButton.active = preset == null;
        presetSaveButton.setTooltip(Tooltip.create(preset == null ? Component.translatable("gui.nullspace.preset.save.tip", presetName.trim()) : preset));
    }

    private void invite() {
        if (inviteBlocker() != null) return;
        send(TerminalAction.Kind.INVITE, inviteName.trim(), presetIdx, kind | (inviteMask << 8), kind == 1 ? HOURS[hoursIdx] : 0);
    }


    private static final String[] SETTING_KEYS = {"keepinventory", "keepxp", "spawn"};
    private static Component settingName(int i) { return Component.translatable("gui.nullspace.setting." + SETTING_KEYS[i]); }
    private static Component settingHelp(int i) { return Component.translatable("gui.nullspace.setting." + SETTING_KEYS[i] + ".help"); }

    private void settings() {
        for (int i = 0; i < 3; i++) {
            int bit = 1 << i;
            boolean on = (data.requested() & bit) != 0;
            String key = SETTING_KEYS[i];
            add(new Btn("setting:" + key, left + 12, top + 63 + i * 32, SETTING_BTN_W, 16, Component.translatable(on ? "gui.nullspace.on" : "gui.nullspace.off"), () -> send(TerminalAction.Kind.SETTING, key, on ? 0 : 1, 0, 0)))
                .style(on ? Btn.Style.ACCENT : Btn.Style.NORMAL).selected(on);
        }
        if (!data.recallInstalled() && data.recallAllowed())
            add(new Btn("recall_install", left + INSTALL_X, top + SETTING_LINE_Y + SETTING_LINE_STEP - 1, INSTALL_W, 11, Component.translatable("gui.nullspace.recall.install"), () -> send(TerminalAction.Kind.INSTALL_RECALL, "", 0, 0, 0))).style(Btn.Style.ACCENT);
        if (!data.hallInstalled() && data.hallAllowed())
            add(new Btn("hall_install", left + INSTALL_X, top + SETTING_LINE_Y + 2 * SETTING_LINE_STEP - 1, INSTALL_W, 11, Component.translatable("gui.nullspace.hall.install"), () -> send(TerminalAction.Kind.INSTALL_HALL, "", 0, 0, 0))).style(Btn.Style.ACCENT);
    }


    private TerminalSnapshot.DestInfo dest() {
        for (var d : data.destinations()) if (d.id() == selectedDest) return d;
        return null;
    }

    private static String shortDimension(String id) {
        return switch (id) { case "minecraft:overworld" -> "gui.nullspace.dim.overworld"; case "minecraft:the_nether" -> "gui.nullspace.dim.nether"; case "minecraft:the_end" -> "gui.nullspace.dim.end"; default -> null; };
    }

    private static Component dimensionText(String id) {
        String key = shortDimension(id);
        return key == null ? Component.literal(clipMiddle(id, DEST_PANE_W)) /* i18n-ok: a modded dimension id */ : Component.translatable(key);
    }

    private static Component dimensionFull(String id) {
        String key = shortDimension(id);
        return key == null ? Component.literal(id) /* i18n-ok: a modded dimension id */ : Component.translatable(key).append(Component.literal(" (" + id + ")")); // i18n-ok: id
    }

    private static String pinLabel(int slot) { return slot < 0 ? "" : String.valueOf(slot + 1); }

    private void destinations() {
        if (!data.hallInstalled()) {
            if (data.hallAllowed()) add(new Btn("hall_install", left + W / 2 - 50, top + 130, 100, 18, Component.translatable("gui.nullspace.hall.install"), () -> send(TerminalAction.Kind.INSTALL_HALL, "", 0, 0, 0))).style(Btn.Style.ACCENT);
            return;
        }
        List<TerminalSnapshot.DestInfo> list = data.destinations();
        if (selectedDest < 0 || dest() == null) selectedDest = list.isEmpty() ? -1 : list.get(0).id();
        destScroll = Math.max(0, Math.min(destScroll, Math.max(0, list.size() - DEST_ROWS)));
        for (int i = 0; i < DEST_ROWS && destScroll + i < list.size(); i++) {
            var d = list.get(destScroll + i);
            Btn row = add(new Btn("dest:" + d.id(), left + DEST_ROW_X, top + 58 + i * DEST_ROW_H, DEST_ROW_W, DEST_ROW_H - 1, Component.literal(d.name()), () -> { selectedDest = d.id(); deleteArmed = -1; rebuildWidgets(); })) // i18n-ok: player-chosen name
                .selected(d.id() == selectedDest).leftAligned().pad(DEST_NAME_X - TEXT_PAD, DEST_BADGE_W);
            row.setTooltip(Tooltip.create(destTip(d)));
        }
        if (list.size() > DEST_ROWS) {
            add(new Btn("dest-up", left + 118, top + 45, 14, 11, Component.literal("^"), () -> { destScroll--; rebuildWidgets(); })).enabled(destScroll > 0);
            add(new Btn("dest-down", left + 134, top + 45, 14, 11, Component.literal("v"), () -> { destScroll++; rebuildWidgets(); })).enabled(destScroll + DEST_ROWS < list.size());
        }
        var d = dest();
        int x = left + DEST_PANE_X;
        if (d != null) {
            Btn rename = add(new Btn("dest-rename", x, top + 94, DEST_PANE_W, 16, Component.translatable("gui.nullspace.dest.rename"), () -> send(TerminalAction.Kind.DEST_RENAME, destName.trim(), d.id(), 0, 0)));
            rename.active = !destName.isBlank();
            rename.setTooltip(Tooltip.create(Component.translatable("gui.nullspace.dest.rename.tip")));
            int slot = d.slot();
            add(new Btn("dest-pad", x, top + 113, DEST_PANE_W, 16, Component.translatable(slot < 0 ? "gui.nullspace.dest.pad.none" : "gui.nullspace.dest.pad", slot + 1), () -> send(TerminalAction.Kind.DEST_BIND, "", d.id(), nextPad(d), 0)))
                .setTooltip(Tooltip.create(Component.translatable("gui.nullspace.dest.pad.tip")));
            add(new Btn("dest-icon", x, top + 132, 64, 16, Component.translatable("gui.nullspace.dest.icon"), () -> send(TerminalAction.Kind.DEST_ICON, "", d.id(), nextIcon(d.icon()), 0)))
                .setTooltip(Tooltip.create(Component.translatable("gui.nullspace.dest.icon.tip")));
            boolean armed = d.id() == deleteArmed;
            add(new Btn("dest-delete", x + 68, top + 132, DEST_PANE_W - 68, 16, Component.translatable(armed ? "gui.nullspace.remove.sure" : "gui.nullspace.dest.delete"), () -> {
                if (deleteArmed == d.id()) { deleteArmed = -1; send(TerminalAction.Kind.DEST_DELETE, "", d.id(), 0, 0); }
                else { deleteArmed = d.id(); rebuildWidgets(); }
            })).style(armed ? Btn.Style.DANGER : Btn.Style.NORMAL);
        }
        destBox = new Field(font, left + DEST_ROW_X, top + 156, DEST_ROW_W + 2, 16, Component.translatable("gui.nullspace.dest.name"));
        destBox.setMaxLength(32); destBox.setHint(hint("gui.nullspace.dest.name", DEST_ROW_W + 2 - 8)); destBox.setValue(destName);
        destBox.setResponder(s -> { destName = s; refreshDest(); });
        addRenderableWidget(destBox);
        boolean full = list.size() >= data.maxDestinations();
        Btn capture = add(new Btn("dest-capture", left + DEST_ROW_X, top + 176, DEST_ROW_W + 2, 16, Component.translatable("gui.nullspace.dest.capture"), () -> send(TerminalAction.Kind.DEST_CAPTURE, destName.trim(), 0, 0, 0)).style(Btn.Style.ACCENT));
        capture.active = !full && !destName.isBlank() && data.hasEntry();
        capture.setTooltip(Tooltip.create(Component.translatable(full ? "gui.nullspace.dest.full" : !data.hasEntry() ? "gui.nullspace.dest.capture.none" : destName.isBlank() ? "gui.nullspace.dest.capture.name" : "gui.nullspace.dest.capture.tip")));
        Btn card = add(new Btn("dest-import", x, top + 176, DEST_PANE_W, 16, Component.translatable("gui.nullspace.dest.import"), () -> send(TerminalAction.Kind.DEST_IMPORT, destName.trim(), 0, 0, 0)));
        card.active = !full;
        card.setTooltip(Tooltip.create(Component.translatable(full ? "gui.nullspace.dest.full" : "gui.nullspace.dest.import.tip")));
    }

    private static Component destTip(TerminalSnapshot.DestInfo d) {
        return Component.literal(d.name()).append("\n").append(dimensionFull(d.dimension())).append("\n") // i18n-ok: player-chosen name
            .append(Component.translatable("gui.nullspace.dest.coords", d.x(), d.y(), d.z()));
    }

    private int nextPad(TerminalSnapshot.DestInfo d) {
        for (int slot = d.slot() + 1; slot < 12; slot++) {
            int s = slot;
            if (data.destinations().stream().noneMatch(o -> o.id() != d.id() && o.slot() == s)) return slot + 1;
        }
        return 0;
    }

    private void refreshDest() {
        if (tab != 3 || destBox == null) return;
        var rename = findBtn("dest-rename");
        if (rename != null) rename.active = !destName.isBlank();
        var capture = findBtn("dest-capture");
        if (capture != null) capture.active = data.destinations().size() < data.maxDestinations() && !destName.isBlank() && data.hasEntry();
    }

    private Btn findBtn(String id) { for (var c : children()) if (c instanceof Btn b && b.id.equals(id)) return b; return null; }

    private static int nextIcon(String current) {
        String[] icons = HubDestinations.ICONS;
        for (int i = 0; i < icons.length; i++) if (icons[i].equals(current)) return (i + 1) % icons.length;
        return 1;
    }


    private static Component remaining(long ms) {
        if (ms <= 0) return Component.translatable("gui.nullspace.remaining.expired");
        long m = ms / 60000, h = m / 60, d = h / 24;
        return d > 0 ? Component.translatable("gui.nullspace.remaining.dh", d, h % 24) : h > 0 ? Component.translatable("gui.nullspace.remaining.hm", h, m % 60) : Component.translatable("gui.nullspace.remaining.m", Math.max(1, m));
    }

    private static Component kindText(TerminalSnapshot.GuestInfo g) {
        return switch (g.kind()) {
            case 0 -> Component.translatable("gui.nullspace.kind.0");
            case 1 -> Component.translatable("gui.nullspace.kind.timed", remaining(g.remainingMillis()));
            default -> Component.translatable(g.used() ? "gui.nullspace.kind.2.used" : "gui.nullspace.kind.2");
        };
    }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.pose().pushPose(); g.pose().scale(1f / scale, 1f / scale, 1f); // the dimmed backdrop covers the real window, not the scaled panel space
        super.renderBackground(g, mouseX, mouseY, partial);
        g.pose().popPose();
        clipped.clear();
        panel(g, left, top, W, H);
        g.fill(left, top, left + W, top + 21, BAND);
        g.fill(left, top + 21, left + W, top + 22, EDGE);
        String title = Component.translatable("gui.nullspace.title").getString();
        int titleW = Math.min(font.width(title), 150);
        label(g, title, 150, left + TITLE_X, top + 7, AMBER);
        label(g, data.owner(), ownerWidth(titleW), left + ownerX(titleW), top + 7, DIM);
        drawStatus(g, font, left, top, data.message(), summary(), data.failed());
        switch (tab) { case 0 -> backRooms(g); case 1 -> backGuests(g); case 2 -> backSettings(g); default -> backDestinations(g); }
    }

    static void drawStatus(GuiGraphics g, Font font, int left, int top, Component message, Component fallback, boolean failed) {
        inset(g, left + 6, top + STATUS_Y, W - 12, STATUS_H);
        boolean noMessage = message.getString().isEmpty();
        List<String> lines = TextFit.wrapLimit(font::width, (noMessage ? fallback : message).getString(), STATUS_TEXT_W, STATUS_LINES);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), left + 11, top + STATUS_Y + (lines.size() > 1 ? 2 : 7) + i * 10, noMessage ? DIM : failed ? BAD : AMBER, false);
    }

    static boolean statusTruncated(Font font, Component message) {
        return TextFit.wrap(font::width, message.getString(), STATUS_TEXT_W).size() > STATUS_LINES;
    }

    private Component summary() {
        return Component.translatable("gui.nullspace.summary", data.rooms().size(), data.maxRooms(), data.guests().size(), data.maxGuests(),
            Component.translatable(data.anchorInstalled() ? "gui.nullspace.anchor.installed" : "gui.nullspace.anchor.none"));
    }

    private void backRooms(GuiGraphics g) {
        label(g, Component.translatable("gui.nullspace.rooms.count", data.rooms().size(), data.maxRooms()).getString(), 62, left + 10, top + 46, DIM);
        inset(g, left + 8, top + 56, 98, ROOM_ROWS * ROOM_ROW_H + 3);
        var room = room();
        if (room == null) return;
        int x = left + 112;
        if (dialogOpen()) {
            inset(g, x - 2, top + 44, 182, 150);
            Component title = pending.kind() == TerminalAction.Kind.EXPAND ? Component.translatable(pending.b() == 1 ? "gui.nullspace.dialog.raise" : "gui.nullspace.dialog.expand", pending.a())
                : Component.translatable("gui.nullspace.dialog.annex", Component.translatable("message.nullspace.dir." + Direction.values()[Math.floorMod(pending.b(), 4)].name().toLowerCase(Locale.ROOT)), pending.a());
            label(g, title.getString(), 174, x + 2, top + 48, AMBER);
            g.fill(x + 2, top + 58, x + 174, top + 59, INSET_EDGE);
            List<FormattedCharSequence> lines = font.split(data.preview(), 166);
            previewScroll = Math.max(0, Math.min(previewScroll, Math.max(0, lines.size() - DIALOG_LINES)));
            for (int i = 0; i < DIALOG_LINES && previewScroll + i < lines.size(); i++) g.drawString(font, lines.get(previewScroll + i), x + 4, top + 63 + i * 10, IVORY, false);
            label(g, (lines.size() > DIALOG_LINES ? Component.translatable("gui.nullspace.dialog.scroll", previewScroll + 1, Math.min(lines.size(), previewScroll + DIALOG_LINES), lines.size()) : Component.translatable("gui.nullspace.dialog.permanent")).getString(), DIALOG_TEXT_W, x + 4, top + 158, DIM);
            return;
        }
        label(g, Component.translatable("gui.nullspace.room.title", room.id(), room.width(), room.height()).getString(), 178, x, top + 46, AMBER);
        label(g, Component.translatable("gui.nullspace.paint.title").getString(), ROOM_PANE_W, x, top + 117, DIM);
        if (paintSurface >= 0 && paintSurface < room.finishes().size()) label(g, Component.translatable("gui.nullspace.paint.finish_for", surfaceLong(paintSurface)).getString(), ROOM_PANE_W, x, top + 162, IVORY);
        else label(g, Component.translatable("gui.nullspace.paint.pick").getString(), ROOM_PANE_W, x, top + 178, FAINT);
    }

    private static Component cardRemaining(TerminalSnapshot.CardInfo c) { return remaining(c.remainingMillis()); }

    private Component cardTitle(TerminalSnapshot.CardInfo c) {
        String number = "#" + c.number();
        if (c.state() != 0) return Component.translatable("gui.nullspace.card.row.dead", number, cardKindName(c.kind()));
        return c.kind() == 1 ? Component.translatable("gui.nullspace.card.row.1", number, cardRemaining(c)) : Component.translatable("gui.nullspace.card.row." + Math.floorMod(c.kind(), 3), number);
    }

    private Component cardLine(TerminalSnapshot.CardInfo c) {
        return switch (c.state()) {
            case 1 -> Component.translatable("gui.nullspace.card.line.used");
            case 2 -> Component.translatable("gui.nullspace.card.line.expired");
            case 3 -> Component.translatable("gui.nullspace.card.line.revoked");
            default -> c.holderOnly() ? Component.translatable("gui.nullspace.card.line.holder")
                : c.boundName().isEmpty() ? Component.translatable("gui.nullspace.card.line.unused") : Component.literal(c.boundName()); // i18n-ok: a player's name
        };
    }

    private List<Component> cardTooltip(TerminalSnapshot.CardInfo c) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.nullspace.card.tip.title", c.number(), cardKindName(c.kind())));
        if (c.state() == 0 && c.kind() == 1) lines.add(Component.translatable("gui.nullspace.card.tip.timed", cardRemaining(c)));
        if (c.state() == 0) {
            lines.add(c.holderOnly() ? Component.translatable("gui.nullspace.card.tip.holder")
                : c.boundName().isEmpty() ? Component.translatable("gui.nullspace.card.tip.unused") : Component.translatable("gui.nullspace.card.tip.bound", Component.literal(c.boundName()))); // i18n-ok: a player's name
        } else {
            lines.add(cardLine(c));
            if (!c.boundName().isEmpty() && !c.holderOnly()) lines.add(Component.translatable("gui.nullspace.card.tip.bound", Component.literal(c.boundName()))); // i18n-ok: a player's name
        }
        if (c.uses() > 0) lines.add(c.uses() == 1 ? Component.translatable("gui.nullspace.card.tip.uses.one") : Component.translatable("gui.nullspace.card.tip.uses", c.uses()));
        if (c.state() != 0) lines.add(Component.translatable("gui.nullspace.card.tip.dead"));
        return lines;
    }

    private void backCards(GuiGraphics g) {
        List<TerminalSnapshot.CardInfo> list = data.cards();
        final int rows = GUEST_ROWS;
        if (list.size() > rows) {
            Component span = Component.translatable("gui.nullspace.guests.span", cardScroll + 1, Math.min(list.size(), cardScroll + rows), list.size());
            String sp = clip(span.getString(), SPAN_W); g.drawString(font, sp, left + SPAN_RIGHT - font.width(sp), top + 46, DIM, false);
        }
        inset(g, left + 8, top + 56, W - 16, GUEST_ROWS * ROW_H + 4);
        if (list.isEmpty()) {
            int y = centred(g, Component.translatable("gui.nullspace.cards.none"), left + W / 2, top + 76, INNER - 20, DIM);
            centred(g, Component.translatable("gui.nullspace.cards.none.hint"), left + W / 2, y + 2, INNER - 20, FAINT);
        }
        for (int i = 0; i < rows && cardScroll + i < list.size(); i++) {
            var c = list.get(cardScroll + i);
            int y = top + 58 + i * ROW_H;
            if (i > 0) g.fill(left + 10, y, left + W - 10, y + 1, 0xFF241E18);
            g.fill(left + GUEST_DOT_X, y + 4, left + GUEST_DOT_X + 4, y + 8, c.state() == 0 ? GOOD : c.state() == 3 ? BAD : 0xFF3A3026);
            label(g, cardTitle(c).getString(), GUEST_TEXT_W, left + GUEST_TEXT_X, y + 2, c.state() == 0 ? IVORY : DIM);
            label(g, cardLine(c).getString(), GUEST_TEXT_W, left + GUEST_TEXT_X, y + 11, c.state() == 0 ? (c.boundName().isEmpty() ? DIM : GOOD) : c.state() == 3 ? BAD : FAINT);
        }
        g.fill(left + 8, top + 140, left + W - 8, top + 141, INSET_EDGE);
        var cs = data.cardSettings();
        label(g, (cs.enabled() ? Component.translatable("gui.nullspace.card.stock", cs.blank()) : Msg.disabledText("cards")).getString(), FULL_LINE_W, left + 10, top + 142 + 46,
            !cs.enabled() || cs.blank() <= 0 ? BAD : FAINT);
    }

    private void backGuests(GuiGraphics g) {
        if (cardMode) { backCards(g); return; }
        List<TerminalSnapshot.GuestInfo> guests = data.guests();
        final int rows = guestRows(), off = teamRows();
        if (guests.size() > rows) {
            Component span = Component.translatable("gui.nullspace.guests.span", guestScroll + 1, Math.min(guests.size(), guestScroll + rows), guests.size());
            { String sp = clip(span.getString(), SPAN_W); g.drawString(font, sp, left + SPAN_RIGHT - font.width(sp), top + 46, DIM, false); }
        }
        inset(g, left + 8, top + 56, W - 16, GUEST_ROWS * ROW_H + 4);
        if (off > 0) teamBack(g, top + 58);
        if (guests.isEmpty()) {
            int y = centred(g, Component.translatable("gui.nullspace.guests.none"), left + W / 2, top + 76 + off * ROW_H, INNER - 20, DIM);
            centred(g, Component.translatable("gui.nullspace.guests.none.hint"), left + W / 2, y + 2, INNER - 20, FAINT);
        }
        for (int i = 0; i < rows && guestScroll + i < guests.size(); i++) {
            var gi = guests.get(guestScroll + i);
            int y = top + 58 + (i + off) * ROW_H;
            if (i > 0) g.fill(left + 10, y, left + W - 10, y + 1, 0xFF241E18);
            g.fill(left + GUEST_DOT_X, y + 4, left + GUEST_DOT_X + 4, y + 8, gi.inside() ? GOOD : 0xFF3A3026);
            label(g, gi.name(), GUEST_TEXT_W, left + GUEST_TEXT_X, y + 2, IVORY);
            label(g, kindText(gi).getString(), GUEST_TEXT_W, left + GUEST_TEXT_X, y + 11, gi.inside() ? GOOD : DIM);
        }
        g.fill(left + 8, top + 140, left + W - 8, top + 141, INSET_EDGE);
    }

    private String teamTitle() {
        var team = data.team();
        return TerminalLayout.teamTitle(font::width, Component.translatable("gui.nullspace.team.title", "%1$s").getString(), Component.translatable("gui.nullspace.team.title.none").getString(), team.name());
    }

    private String teamLine() {
        var team = data.team();
        if (team.name().isEmpty()) return Component.translatable("gui.nullspace.team.line.solo").getString();
        if (team.on() && team.status() != 2) return Component.translatable("gui.nullspace.team.state.paused").getString();
        return (team.size() == 1 ? Component.translatable("gui.nullspace.team.members.one") : Component.translatable("gui.nullspace.team.members", team.size())).getString();
    }

    private void teamBack(GuiGraphics g, int y) {
        var team = data.team();
        g.fill(left + GUEST_DOT_X, y + 4, left + GUEST_DOT_X + 4, y + 8, team.status() == 2 ? GOOD : team.on() ? BAD : 0xFF3A3026);
        label(g, teamTitle(), GUEST_TEXT_W, left + GUEST_TEXT_X, y + 2, AMBER);
        label(g, teamLine(), GUEST_TEXT_W, left + GUEST_TEXT_X, y + 11, team.status() == 2 ? GOOD : team.on() ? BAD : DIM);
        g.fill(left + 10, y + ROW_H - 1, left + W - 10, y + ROW_H, 0xFF241E18);
    }

    private List<Component> teamTooltip() {
        var team = data.team();
        List<Component> lines = new ArrayList<>();
        lines.add(team.name().isEmpty() ? Component.translatable("gui.nullspace.team.title.none")
            : Component.translatable("gui.nullspace.team.full", Component.literal(team.name()), team.size())); // i18n-ok: party name typed by a player
        String key = switch (team.status()) {
            case 2 -> team.mode() == 2 ? "gui.nullspace.team.tip.active.allies" : "gui.nullspace.team.tip.active";
            case 3 -> "gui.nullspace.team.tip.no_team";
            case 4 -> "gui.nullspace.team.tip.suspended";
            case 5 -> "gui.nullspace.team.tip.too_large";
            default -> team.name().isEmpty() ? "gui.nullspace.team.tip.solo" : "gui.nullspace.team.tip.idle";
        };
        lines.add(Component.translatable(key, team.maxSize()));
        return lines;
    }

    private void backSettings(GuiGraphics g) {
        label(g, Component.translatable("gui.nullspace.settings.intro").getString(), FULL_LINE_W, left + 10, top + 46, DIM);
        for (int i = 0; i < 3; i++) {
            int y = top + 56 + i * 32;
            inset(g, left + 8, y, W - 16, 30);
            boolean req = (data.requested() & (1 << i)) != 0, eff = (data.effective() & (1 << i)) != 0;
            Component status = req && !eff ? Component.translatable("gui.nullspace.setting.blocked") : eff ? Component.translatable("gui.nullspace.setting.in_effect") : req ? Component.empty() : Component.translatable("gui.nullspace.off");
            int statusW = Math.min(font.width(status), SETTING_TEXT_W / 2);
            label(g, settingName(i).getString(), SETTING_TEXT_W - statusW - 8, left + SETTING_TEXT_X, y + 6, IVORY);
            label(g, status.getString(), statusW, left + SETTING_RIGHT - statusW, y + 6, req && !eff ? BAD : eff ? GOOD : FAINT);
            label(g, settingHelp(i).getString(), SETTING_TEXT_W, left + SETTING_TEXT_X, y + 17, DIM);
        }
        int y = top + SETTING_LINE_Y;
        label(g, Component.translatable(data.anchorInstalled() ? "gui.nullspace.anchor.line.installed" : "gui.nullspace.anchor.line.not_installed").getString()
            + (data.anchorAllowed() ? "" : Component.translatable("gui.nullspace.anchor.disabled").getString()), FULL_LINE_W, left + 10, y, IVORY);
        label(g, Component.translatable(data.recallInstalled() ? "gui.nullspace.recall.line.installed" : "gui.nullspace.recall.line.not_installed").getString()
            + (data.recallAllowed() ? "" : Component.translatable("gui.nullspace.recall.disabled").getString()), data.recallInstalled() || !data.recallAllowed() ? FULL_LINE_W : MODULE_LINE_W, left + 10, y + SETTING_LINE_STEP, data.recallInstalled() && data.recallAllowed() ? GOOD : IVORY);
        label(g, Component.translatable(data.hallInstalled() ? "gui.nullspace.hall.line.installed" : "gui.nullspace.hall.line.not_installed").getString()
            + (data.hallAllowed() ? "" : Component.translatable("gui.nullspace.hall.disabled").getString()), data.hallInstalled() || !data.hallAllowed() ? FULL_LINE_W : MODULE_LINE_W, left + 10, y + 2 * SETTING_LINE_STEP, data.hallInstalled() && data.hallAllowed() ? GOOD : IVORY);
        label(g, Component.translatable(data.hallInstalled() ? "gui.nullspace.hall.help" : data.recallInstalled() ? "gui.nullspace.recall.help" : "gui.nullspace.anchor.hint", data.recallMinutes()).getString(), FULL_LINE_W, left + 10, y + 3 * SETTING_LINE_STEP, FAINT);
    }

    private int centred(GuiGraphics g, Component text, int centreX, int y, int width, int colour) {
        for (FormattedCharSequence line : font.split(text, width)) { g.drawString(font, line, centreX - font.width(line) / 2, y, colour, false); y += 10; }
        return y;
    }

    private void backDestinations(GuiGraphics g) {
        if (!data.hallInstalled()) {
            int y = centred(g, Component.translatable("gui.nullspace.dest.none_hall"), left + W / 2, top + 66, NOHALL_W, DIM) + 4;
            y = centred(g, Component.translatable("gui.nullspace.dest.none_hall.hint"), left + W / 2, y, NOHALL_W, FAINT);
            if (!data.hallAllowed()) centred(g, Msg.disabledText("hall"), left + W / 2, Math.max(y + 14, top + 134), NOHALL_W, BAD);
            return;
        }
        List<TerminalSnapshot.DestInfo> list = data.destinations();
        label(g, Component.translatable("gui.nullspace.dest.count", list.size(), data.maxDestinations()).getString(), 106, left + 10, top + 46, DIM);
        inset(g, left + DEST_LIST_X, top + 56, DEST_LIST_W, DEST_ROWS * DEST_ROW_H + 3);
        if (list.isEmpty()) {
            int y = centred(g, Component.translatable("gui.nullspace.dest.none"), left + DEST_LIST_X + DEST_LIST_W / 2, top + 76, DEST_EMPTY_W, DIM) + 2;
            centred(g, Component.translatable("gui.nullspace.dest.none.hint"), left + DEST_LIST_X + DEST_LIST_W / 2, y, DEST_EMPTY_W, FAINT);
        }
        inset(g, left + DEST_DETAIL_X, top + 56, DEST_DETAIL_W, 96);
        var d = dest();
        int x = left + DEST_PANE_X;
        if (d == null) { centred(g, Component.translatable("gui.nullspace.dest.pick"), left + DEST_DETAIL_X + DEST_DETAIL_W / 2, top + 100, DEST_PANE_W - 8, FAINT); }
        else {
            label(g, d.name(), DEST_NAME_HEAD_W, x + 16, top + 62, AMBER);
            g.drawString(font, dimensionText(d.dimension()), x, top + 75, IVORY, false);
            label(g, TerminalLayout.coords(d.x(), d.y(), d.z()), DEST_PANE_W, x, top + 85, DIM);
        }
        g.fill(left + 8, top + 152, left + W - 8, top + 153, INSET_EDGE);
        label(g, Component.translatable(data.chrononCost() > 0 ? "gui.nullspace.dest.cost" : "gui.nullspace.dest.free", data.chrononCost()).getString(), DEST_PANE_W, x, top + 160, FAINT);
    }

    private void padBadges(GuiGraphics g) {
        if (tab != 3 || !data.hallInstalled()) return;
        List<TerminalSnapshot.DestInfo> list = data.destinations();
        for (int i = 0; i < DEST_ROWS && destScroll + i < list.size(); i++) {
            var d = list.get(destScroll + i);
            drawIcon(g, d.icon(), left + DEST_ROW_X + 3, top + 58 + i * DEST_ROW_H + 1, 0.75f);
            if (d.slot() < 0) continue;
            String n = pinLabel(d.slot());
            g.drawString(font, n, left + DEST_ROW_X + DEST_ROW_W - 4 - font.width(n), top + 58 + i * DEST_ROW_H + 3, d.id() == selectedDest ? 0xFF1B1510 : AMBER, false);
        }
    }

    private static final Map<String, ItemStack> ICON_STACKS = new HashMap<>();

    private void drawIcon(GuiGraphics g, String id, int x, int y, float scale) {
        if (id.isEmpty()) return;
        var stack = ICON_STACKS.computeIfAbsent(id, key -> {
            var rl = ResourceLocation.tryParse(key);
            var item = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
            return new ItemStack(item);
        });
        if (stack.isEmpty()) return;
        g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(scale, scale, 1f);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    @Override public void render(GuiGraphics g, int rawMouseX, int rawMouseY, float partial) {
        g.pose().pushPose(); g.pose().scale(scale, scale, 1f);
        renderScaled(g, (int) (rawMouseX / scale), (int) (rawMouseY / scale), partial);
        g.pose().popPose();
    }

    private void renderScaled(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        for (int i = 0; i < TABS.length; i++) g.blitSprite(sprite(TAB_ICONS[i]), left + tabX[i] + 3, top + TAB_Y + 2, TAB_ICON, TAB_ICON);
        padBadges(g);
        for (Clipped c : clipped) if (mouseX >= c.x() && mouseX < c.x() + c.w() && mouseY >= c.y() && mouseY < c.y() + 10) { g.renderTooltip(font, font.split(Component.literal(c.full()), 180), mouseX, mouseY); break; } // i18n-ok: an already translated label
        if (tab == 3 && data.hallInstalled() && dest() != null) drawIcon(g, dest().icon(), left + DEST_PANE_X, top + 60, 0.75f);
        if (tab == 2) {
            for (int i = 0; i < 3; i++) {
                int y = top + 56 + i * 32;
                if (mouseX >= left + 8 && mouseX < left + W - 8 && mouseY >= y && mouseY < y + 30 && (data.requested() & (1 << i)) != 0 && (data.effective() & (1 << i)) == 0)
                    g.renderTooltip(font, font.split(Component.translatable("gui.nullspace.setting.blocked.tip"), 190), mouseX, mouseY);
            }
        }
        if (mouseX >= left + 6 && mouseX < left + W - 6 && mouseY >= top + STATUS_Y && mouseY < top + STATUS_Y + STATUS_H && statusTruncated(font, data.message()))
            g.renderTooltip(font, font.split(data.message(), 200), mouseX, mouseY);
        if (tab == 1 && cardMode) {
            List<TerminalSnapshot.CardInfo> cards = data.cards();
            for (int i = 0; i < GUEST_ROWS && cardScroll + i < cards.size(); i++) {
                int y = top + 58 + i * ROW_H;
                if (mouseX >= left + 10 && mouseX < left + PERM_X - 2 && mouseY >= y && mouseY < y + ROW_H) {
                    List<FormattedCharSequence> wrapped = new ArrayList<>();
                    for (Component line : cardTooltip(cards.get(cardScroll + i))) wrapped.addAll(font.split(line, 190));
                    g.renderTooltip(font, wrapped, mouseX, mouseY);
                }
            }
        } else if (tab == 1) {
            List<TerminalSnapshot.GuestInfo> guests = data.guests();
            if (teamRows() > 0 && mouseX >= left + 10 && mouseX < left + PERM_X - 2 && mouseY >= top + 58 && mouseY < top + 58 + ROW_H) {
                List<FormattedCharSequence> wrapped = new ArrayList<>();
                for (Component line : teamTooltip()) wrapped.addAll(font.split(line, 190));
                g.renderTooltip(font, wrapped, mouseX, mouseY);
            }
            for (int i = 0; i < guestRows() && guestScroll + i < guests.size(); i++) {
                int y = top + 58 + (i + teamRows()) * ROW_H;
                if (mouseX >= left + 10 && mouseX < left + PERM_X - 2 && mouseY >= y && mouseY < y + ROW_H) {
                    var gi = guests.get(guestScroll + i);
                    g.renderComponentTooltip(font, List.of(Component.literal(gi.name()), kindText(gi).copy().append(gi.inside() ? Component.translatable("gui.nullspace.guests.inside_note") : Component.empty())), mouseX, mouseY);
                }
            }
        }
    }


    @Override public boolean mouseClicked(double mx, double my, int button) { return super.mouseClicked(mx / scale, my / scale, button); }
    @Override public boolean mouseReleased(double mx, double my, int button) { return super.mouseReleased(mx / scale, my / scale, button); }
    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) { return super.mouseDragged(mx / scale, my / scale, button, dx / scale, dy / scale); }
    @Override public void mouseMoved(double mx, double my) { super.mouseMoved(mx / scale, my / scale); }

    @Override public boolean mouseScrolled(double rawX, double rawY, double sx, double sy) {
        double mx = rawX / scale, my = rawY / scale;
        int dir = sy > 0 ? -1 : 1;
        if (tab == 1 && my >= top + 56 && my < top + 140) { if (cardMode) cardScroll += dir; else guestScroll += dir; rebuildWidgets(); return true; }
        if (tab == 0 && dialogOpen()) { previewScroll += dir; return true; }
        if (tab == 0 && mx < left + 108 && data.rooms().size() > ROOM_ROWS) { roomScroll += dir; rebuildWidgets(); return true; }
        if (tab == 3 && data.hallInstalled() && mx < left + 160 && my >= top + 56 && my < top + 150) { destScroll += dir; rebuildWidgets(); return true; }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if ((key == 257 || key == 335) && tab == 1 && nameBox != null && nameBox.isFocused() && inviteButton != null && inviteButton.active) { invite(); return true; }
        if ((key == 257 || key == 335) && tab == 3 && destBox != null && destBox.isFocused()) {
            Btn rename = findBtn("dest-rename"), capture = findBtn("dest-capture");
            if (capture != null && capture.active) { capture.onPress(); return true; }
            if (rename != null && rename.active) { rename.onPress(); return true; }
        }
        if ((key == 257 || key == 335) && tab == 1 && presetBox != null && presetBox.isFocused() && presetSaveButton != null && presetSaveButton.active) { presetSaveButton.onPress(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override public boolean isPauseScreen() { return false; }


    List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (var c : children()) if (c instanceof Btn b) ids.add(b.id);
        return ids;
    }

    boolean click(String id) {
        for (var c : children()) if (c instanceof Btn b && b.id.equals(id)) {
            double cx = (b.getX() + b.getWidth() / 2.0) * scale, cy = (b.getY() + b.getHeight() / 2.0) * scale;
            boolean handled = mouseClicked(cx, cy, 0);
            mouseReleased(cx, cy, 0);
            return handled;
        }
        return false;
    }

    String label(String id) {
        for (var c : children()) if (c instanceof Btn b && b.id.equals(id)) return b.getMessage().getString();
        return "";
    }

    boolean isActive(String id) {
        for (var c : children()) if (c instanceof Btn b && b.id.equals(id)) return b.active;
        return false;
    }

    void typeName(String text) { if (nameBox != null) { nameBox.setFocused(true); nameBox.setValue(text); } }
    void typeDest(String text) { if (destBox != null) { destBox.setFocused(true); destBox.setValue(text); } }
    void typePreset(String text) { if (presetBox != null) { presetBox.setFocused(true); presetBox.setValue(text); } }
    void setTab(int t) { tab = t; pending = null; rebuildWidgets(); }
    void setCardMode(boolean on) { cardMode = on; rebuildWidgets(); }
    double[] centre(String id) {
        if (id.equals("team-row")) return new double[] {(left + GUEST_TEXT_X + 20) * scale, (top + 58 + ROW_H / 2.0) * scale};
        for (var c : children()) if (c instanceof Btn b && b.id.equals(id)) return new double[] {(b.getX() + b.getWidth() / 2.0) * scale, (b.getY() + b.getHeight() / 2.0) * scale};
        return null;
    }
    TerminalSnapshot snapshot() { return data; }
    int tabIndex() { return tab; }
    float layoutScale() { return scale; }
    void selectRoom(int id) { selectedRoom = id; }
}
