package dev.nullspace.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

// every translated label in a fixed region of TerminalLayout has to fit it with the worst data the server can send
// (16 character guests, 32 character names, nine digit coordinates, a 30 day invitation, room 8 at 29x29x12),
// measured with a font width function calibrated against the game font. the same strings pseudo localised to be 40%
// longer must fit or be allowed to cut
class TerminalLayoutTest {
    private static final Path LANG = Path.of("src/main/resources/assets/nullspace/lang/en_us.json");

    // vanilla default font advance widths in pixels (glyph plus one pixel)
    static int width(String text) {
        int w = 0;
        for (char c : text.toCharArray()) w += glyph(c);
        return w;
    }

    private static int glyph(char c) {
        if (c == ' ' || c == '"' || c == '(' || c == ')' || c == '*' || c == '{' || c == '}' || c == 'I' || c == 't' || c == '[' || c == ']') return 4;
        if ("i!.,:;|'".indexOf(c) >= 0) return 2;
        if (c == '`' || c == 'l') return 3;
        if ("fk<>".indexOf(c) >= 0) return 5;
        if (c == '@' || c == '~') return 7;
        if (c == '\u00d7' || c == '\u00e9' || c == '\u00fc') return 6;
        if (c > 127) return 8; // arrows and the ellipsis glyph
        return 6;
    }

    @Test void fontMatchesTheRealGame() {
        assertEquals(231, width("The quick brown fox jumps over the lazy dog"));
        assertEquals(240, width("THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG"));
        assertEquals(60, width("0123456789"));
        assertEquals(96, width("WWWWWWWWWWWWWWWW"));
        assertEquals(32, width("iiiiiiiiiiiiiiii"));
        assertEquals(30, width("llllllllll"));
        assertEquals(110, width("()[]{}<>*\"@~#%&+-=/?_"));
        assertEquals(21, width(" .,:;!|'`"));
        assertEquals(86, width("Bartholomew_Pike"));
        assertEquals(94, width("Timed, 29d 23h left"));
        assertEquals(265, width("Continuity Anchor: not installed (disabled by server)"));
    }


    private static Map<String, String> lang() throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = Pattern.compile("^\\s*\"([^\"]+)\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",?\\s*$", Pattern.MULTILINE).matcher(Files.readString(LANG, StandardCharsets.UTF_8));
        while (m.find()) map.put(m.group(1), m.group(2).replace("\\n", "\n").replace("\\\"", "\""));
        return map;
    }

    private static String fmt(String pattern, Object... args) {
        Matcher m = Pattern.compile("%(?:(\\d+)\\$)?s").matcher(pattern);
        StringBuilder out = new StringBuilder();
        int next = 0;
        while (m.find()) {
            int index = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++;
            m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(args[index])));
        }
        return m.appendTail(out).toString();
    }

    private static final String[] TABS = {"gui.nullspace.tab.rooms", "gui.nullspace.tab.guests", "gui.nullspace.tab.settings", "gui.nullspace.tab.destinations"};
    private static final String NAME16 = "WWWWWWWWWWWWWWWW";
    private static final String OWNER = "WWWWWWWWWWWWWWWW", NAME32 = "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW";
    private static final String[] DIRS = {"North", "East", "South", "West"};

    // one fixed region and the strings that must fit it. hover: the full text shows on hover when cut, so a longer
    // pseudo string is fine
    private record Slot(String name, int region, boolean hover, List<String> texts) {}

    private static List<Slot> slots(Map<String, String> l, boolean pseudo) {
        List<Slot> out = new ArrayList<>();
        Function<String, String> t = key -> { String v = l.get(key); assertNotNull(v, key); return pseudo ? PseudoLoc.expand(v) : v; };
        BiFunction<String, Object[], String> f = (key, args) -> fmt(t.apply(key), args);
        int tp = TerminalLayout.TEXT_PAD;
        // header
        out.add(new Slot("title", 150, false, List.of(t.apply("gui.nullspace.title"))));
        // rooms
        List<String> rows = new ArrayList<>();
        for (String r : new String[] {t.apply("gui.nullspace.room.start"), f.apply("gui.nullspace.room.n", new Object[] {8})}) rows.add(f.apply("gui.nullspace.room.row", new Object[] {r, 29, 12}));
        out.add(new Slot("room row", TerminalLayout.ROOM_LIST_W - 2 * tp, true, rows));
        out.add(new Slot("room title", TerminalLayout.ROOM_PANE_W - 46, false, List.of(f.apply("gui.nullspace.room.title", new Object[] {8, 29, 12}))));
        out.add(new Slot("rename", 42 - 2 * tp, true, List.of(t.apply("gui.nullspace.room.rename"), t.apply("gui.nullspace.room.rename.save"))));
        out.add(new Slot("room name field hint", TerminalLayout.ROOM_PANE_W - 46 - 8, false, List.of(t.apply("gui.nullspace.room.name"))));
        out.add(new Slot("named room row", TerminalLayout.ROOM_LIST_W - 2 * tp - 6, true, List.of(f.apply("gui.nullspace.room.row_named", new Object[] {8, "Study"}), f.apply("gui.nullspace.room.row_named", new Object[] {t.apply("gui.nullspace.room.start"), "Lobby"}))));
        out.add(new Slot("widen/raise", TerminalLayout.ROOM_BTN_W - 2 * tp, true, List.of(f.apply("gui.nullspace.widen", new Object[] {29}), t.apply("gui.nullspace.widen.max"),
            f.apply("gui.nullspace.raise", new Object[] {12}), t.apply("gui.nullspace.raise.max"))));
        List<String> annex = new ArrayList<>();
        for (String d : DIRS) { annex.add(f.apply("gui.nullspace.annex.add", new Object[] {t.apply("gui.nullspace.dir." + d.toLowerCase())})); annex.add(f.apply("gui.nullspace.annex.joined", new Object[] {t.apply("gui.nullspace.dir." + d.toLowerCase())})); }
        out.add(new Slot("annex", TerminalLayout.ROOM_BTN_W - 2 * tp, true, annex));
        List<String> surfaces = new ArrayList<>();
        for (String s : new String[] {"floor", "ceiling", "north", "east", "south", "west", "walls"}) surfaces.add(t.apply("gui.nullspace.surface." + s));
        out.add(new Slot("surface", TerminalLayout.SURFACE_BTN_W - 2 * tp - 13, true, surfaces));
        List<String> scopes = new ArrayList<>();
        for (String s : new String[] {"room", "all", "tunnels"}) scopes.add(t.apply("gui.nullspace.paint.scope." + s));
        out.add(new Slot("paint scope", TerminalLayout.SURFACE_BTN_W - 2 * tp, true, scopes));
        out.add(new Slot("paint confirm", TerminalLayout.ROOM_PANE_W, false, List.of(f.apply("gui.nullspace.paint.confirm_all", new Object[] {256}), t.apply("gui.nullspace.paint.confirm_tunnels"))));
        out.add(new Slot("dialog title", TerminalLayout.DIALOG_TITLE_W, false, List.of(f.apply("gui.nullspace.dialog.expand", new Object[] {8}), f.apply("gui.nullspace.dialog.raise", new Object[] {8}),
            f.apply("gui.nullspace.dialog.annex", new Object[] {t.apply("message.nullspace.dir.north"), 8}))));
        out.add(new Slot("confirm", 108 - 2 * tp, true, List.of(t.apply("gui.nullspace.confirm"))));
        out.add(new Slot("cancel", 66 - 2 * tp, true, List.of(t.apply("gui.nullspace.cancel"))));
        // guests
        List<String> kinds = new ArrayList<>(List.of(t.apply("gui.nullspace.kind.0"), t.apply("gui.nullspace.kind.2"), t.apply("gui.nullspace.kind.2.used"), f.apply("gui.nullspace.kind.timed", new Object[] {t.apply("gui.nullspace.remaining.expired")})));
        for (String rem : new String[] {f.apply("gui.nullspace.remaining.dh", new Object[] {29, 23}), f.apply("gui.nullspace.remaining.hm", new Object[] {23, 59}), f.apply("gui.nullspace.remaining.m", new Object[] {59})})
            kinds.add(f.apply("gui.nullspace.kind.timed", new Object[] {rem}));
        out.add(new Slot("guest kind line", TerminalLayout.GUEST_TEXT_W, false, kinds));
        out.add(new Slot("remove", TerminalLayout.REMOVE_W - 2 * tp, true, List.of(t.apply("gui.nullspace.remove"), t.apply("gui.nullspace.remove.sure"))));
        out.add(new Slot("invite", TerminalLayout.INVITE_W - 2 * tp, true, List.of(t.apply("gui.nullspace.invite"))));
        out.add(new Slot("kind button", TerminalLayout.KIND_W - 2 * tp, true, List.of(t.apply("gui.nullspace.kind.0"), t.apply("gui.nullspace.kind.2"))));
        out.add(new Slot("kind button (timed)", TerminalLayout.KIND_W_TIMED - 2 * tp, true, List.of(t.apply("gui.nullspace.kind.1"))));
        List<String> hours = new ArrayList<>();
        for (int h : new int[] {1, 6}) hours.add(f.apply("gui.nullspace.duration.hours", new Object[] {h}));
        hours.add(t.apply("gui.nullspace.duration.day")); hours.add(f.apply("gui.nullspace.duration.days", new Object[] {3})); hours.add(f.apply("gui.nullspace.duration.days", new Object[] {30}));
        out.add(new Slot("hours button", TerminalLayout.HOURS_W - 2 * tp, true, hours));
        out.add(new Slot("preset button", TerminalLayout.PRESET_W - 2 * tp, true, List.of(f.apply("gui.nullspace.preset", new Object[] {t.apply("gui.nullspace.preset.custom")}))));
        out.add(new Slot("save preset", TerminalLayout.SAVE_W - 2 * tp, true, List.of(t.apply("gui.nullspace.preset.save"))));
        // team row (the pinned first row of the Guests list)
        out.add(new Slot("team switch", TerminalLayout.REMOVE_W - 2 * tp, true, List.of(t.apply("gui.nullspace.on"), t.apply("gui.nullspace.off"), t.apply("gui.nullspace.team.renew"))));
        List<String> teamLines = new ArrayList<>(List.of(t.apply("gui.nullspace.team.line.solo"), t.apply("gui.nullspace.team.title.none")));
        teamLines.add(t.apply("gui.nullspace.team.members.one")); teamLines.add(f.apply("gui.nullspace.team.members", new Object[] {1024})); teamLines.add(t.apply("gui.nullspace.team.state.paused"));
        out.add(new Slot("team row line", TerminalLayout.GUEST_TEXT_W, false, teamLines));
        // Archive Cards (the second view of the Guests tab)
        out.add(new Slot("guests mode button", TerminalLayout.MODE_GUESTS_W - 2 * tp, true, List.of(f.apply("gui.nullspace.guests.count", new Object[] {32, 32}), f.apply("gui.nullspace.guests.count", new Object[] {64, 64}))));
        out.add(new Slot("cards mode button", TerminalLayout.MODE_CARDS_W - 2 * tp, true, List.of(f.apply("gui.nullspace.cards.count", new Object[] {8, 8}), f.apply("gui.nullspace.cards.count", new Object[] {32, 32}))));
        out.add(new Slot("page span", TerminalLayout.SPAN_W, false, List.of(f.apply("gui.nullspace.guests.span", new Object[] {61, 64, 64}))));
        List<String> cardRows = new ArrayList<>();
        String[] remainings = {f.apply("gui.nullspace.remaining.dh", new Object[] {29, 23}), f.apply("gui.nullspace.remaining.hm", new Object[] {23, 59}), f.apply("gui.nullspace.remaining.m", new Object[] {59}), t.apply("gui.nullspace.remaining.expired")};
        for (String rem : remainings) cardRows.add(f.apply("gui.nullspace.card.row.1", new Object[] {"#64", rem}));
        cardRows.add(f.apply("gui.nullspace.card.row.0", new Object[] {"#64"})); cardRows.add(f.apply("gui.nullspace.card.row.2", new Object[] {"#64"}));
        for (int k = 0; k < 3; k++) cardRows.add(f.apply("gui.nullspace.card.row.dead", new Object[] {"#64", t.apply("gui.nullspace.card.kind." + k)}));
        out.add(new Slot("card row title", TerminalLayout.GUEST_TEXT_W, false, cardRows));
        List<String> cardLines = new ArrayList<>(List.of(NAME16));
        for (String k : new String[] {"unused", "holder", "revoked", "used", "expired"}) cardLines.add(t.apply("gui.nullspace.card.line." + k));
        out.add(new Slot("card row line", TerminalLayout.GUEST_TEXT_W, false, cardLines));
        out.add(new Slot("revoke/clear", TerminalLayout.REMOVE_W - 2 * tp, true, List.of(t.apply("gui.nullspace.card.revoke"), t.apply("gui.nullspace.card.revoke.sure"), t.apply("gui.nullspace.card.forget"))));
        out.add(new Slot("card kind button", TerminalLayout.CARD_KIND_W - 2 * tp, true, List.of(t.apply("gui.nullspace.card.kind.0"), t.apply("gui.nullspace.card.kind.1"), t.apply("gui.nullspace.card.kind.2"))));
        out.add(new Slot("card hours button", TerminalLayout.CARD_HOURS_W - 2 * tp, true, hours));
        out.add(new Slot("card bind button (timed)", TerminalLayout.cardBindW(true) - 2 * tp, true, List.of(t.apply("gui.nullspace.card.bind.first"), t.apply("gui.nullspace.card.bind.holder"))));
        out.add(new Slot("card bind button", TerminalLayout.cardBindW(false) - 2 * tp, true, List.of(t.apply("gui.nullspace.card.bind.first"), t.apply("gui.nullspace.card.bind.holder"))));
        out.add(new Slot("print card", TerminalLayout.CARD_PRINT_W - 2 * tp, true, List.of(t.apply("gui.nullspace.card.print"))));
        out.add(new Slot("card stock line", TerminalLayout.FULL_LINE_W, false, List.of(f.apply("gui.nullspace.card.stock", new Object[] {2304}), f.apply("message.nullspace.disabled", new Object[] {t.apply("message.nullspace.feature.cards")}))));
        out.add(new Slot("cards list empty", TerminalLayout.INNER - 20, false, List.of(t.apply("gui.nullspace.cards.none"), t.apply("gui.nullspace.cards.none.hint"))));
        // settings
        int statusW = 0;
        for (String k : new String[] {"gui.nullspace.setting.blocked", "gui.nullspace.setting.in_effect", "gui.nullspace.off"}) statusW = Math.max(statusW, width(t.apply(k)));
        List<String> names = new ArrayList<>(), helps = new ArrayList<>();
        for (String k : new String[] {"keepinventory", "keepxp", "spawn"}) { names.add(t.apply("gui.nullspace.setting." + k)); helps.add(t.apply("gui.nullspace.setting." + k + ".help")); }
        out.add(new Slot("setting name", TerminalLayout.SETTING_TEXT_W - statusW - 8, false, names));
        out.add(new Slot("setting help", TerminalLayout.SETTING_TEXT_W, false, helps));
        out.add(new Slot("on/off", TerminalLayout.SETTING_BTN_W - 2 * tp, true, List.of(t.apply("gui.nullspace.on"), t.apply("gui.nullspace.off"))));
        out.add(new Slot("settings intro", TerminalLayout.FULL_LINE_W, false, List.of(t.apply("gui.nullspace.settings.intro"))));
        List<String> full = new ArrayList<>(), module = new ArrayList<>();
        for (String k : new String[] {"anchor", "recall", "hall"}) {
            full.add(t.apply("gui.nullspace." + k + ".line.not_installed") + t.apply("gui.nullspace." + k + ".disabled"));
            full.add(t.apply("gui.nullspace." + k + ".line.installed"));
            if (!k.equals("anchor")) module.add(t.apply("gui.nullspace." + k + ".line.not_installed"));
        }
        out.add(new Slot("module line (disabled)", TerminalLayout.FULL_LINE_W, false, full));
        out.add(new Slot("module line (install button)", TerminalLayout.MODULE_LINE_W, false, module));
        out.add(new Slot("settings hint", TerminalLayout.FULL_LINE_W, false, List.of(t.apply("gui.nullspace.hall.help"), f.apply("gui.nullspace.recall.help", new Object[] {120}), t.apply("gui.nullspace.anchor.hint"))));
        out.add(new Slot("install", TerminalLayout.INSTALL_W - 2 * tp, true, List.of(t.apply("gui.nullspace.recall.install"), t.apply("gui.nullspace.hall.install"))));
        // destinations
        out.add(new Slot("dest count", 106, false, List.of(f.apply("gui.nullspace.dest.count", new Object[] {12, 12}))));
        out.add(new Slot("dest coords", TerminalLayout.DEST_PANE_W, false, List.of(TerminalLayout.coords(-29999984, -64, 29999984))));
        out.add(new Slot("dest buttons", TerminalLayout.DEST_PANE_W - 2 * tp, true, List.of(t.apply("gui.nullspace.dest.rename"), f.apply("gui.nullspace.dest.pad", new Object[] {12}), t.apply("gui.nullspace.dest.pad.none"), t.apply("gui.nullspace.dest.import"))));
        out.add(new Slot("dest symbol", 64 - 2 * tp, true, List.of(t.apply("gui.nullspace.dest.icon"))));
        out.add(new Slot("dest delete", TerminalLayout.DEST_PANE_W - 68 - 2 * tp, true, List.of(t.apply("gui.nullspace.dest.delete"), t.apply("gui.nullspace.remove.sure"))));
        out.add(new Slot("dest capture", TerminalLayout.DEST_ROW_W + 2 - 2 * tp, true, List.of(t.apply("gui.nullspace.dest.capture"))));
        out.add(new Slot("dest cost line", TerminalLayout.DEST_PANE_W, false, List.of(t.apply("gui.nullspace.dest.free"), f.apply("gui.nullspace.dest.cost", new Object[] {999}))));
        out.add(new Slot("dest name field hint", TerminalLayout.DEST_ROW_W + 2 - 8, false, List.of(t.apply("gui.nullspace.dest.name"))));
        out.add(new Slot("player name field hint", TerminalLayout.NAME_BOX_W - 8, false, List.of(t.apply("gui.nullspace.player_name"))));
        out.add(new Slot("preset name field hint", TerminalLayout.NAME_BOX_W - 8, false, List.of(t.apply("gui.nullspace.preset_name.new"))));
        return out;
    }


    @Test void everyEnglishLabelFitsItsRegion() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Slot s : slots(lang(), false)) for (String text : s.texts()) if (width(text) > s.region()) bad.add(s.name() + ": '" + text + "' is " + width(text) + " px, region " + s.region());
        assertTrue(bad.isEmpty(), "labels wider than their region:\n" + String.join("\n", bad));
    }

    @Test void fittingHelpersNeverExceedTheRegionEvenWhenPseudoLocalised() throws IOException {
        int overflowing = 0, hoverOnly = 0;
        for (Slot s : slots(lang(), true)) for (String text : s.texts()) {
            assertTrue(width(TextFit.clip(TerminalLayoutTest::width, text, s.region())) <= s.region(), s.name() + ": clipped text still too wide: " + text);
            for (String line : TextFit.wrap(TerminalLayoutTest::width, text, s.region())) assertTrue(width(line) <= s.region(), s.name() + ": wrapped line too wide: " + line);
            if (width(text) > s.region()) { overflowing++; if (s.hover()) hoverOnly++; else System.out.println("  pseudo label cut: " + s.name() + " -> " + text); }
        }
        System.out.println("pseudo-localised (+40%): " + overflowing + " strings are cut by their region, " + hoverOnly + " of them on buttons that show the full text on hover");
        assertTrue(overflowing >= hoverOnly);
    }

    @Test void clipKeepsAnEllipsisAndNeverGrows() {
        assertEquals("abc", TextFit.clip(TerminalLayoutTest::width, "abc", 100));
        String c = TextFit.clip(TerminalLayoutTest::width, "WWWWWWWWWWWWWWWW", 50);
        assertTrue(c.endsWith("...") && width(c) <= 50, c);
        String m = TextFit.clipMiddle(TerminalLayoutTest::width, "mymod:very_long_dimension_name_that_goes_on", 120);
        assertTrue(m.startsWith("mymod:") && m.endsWith("goes_on".substring(3)) && m.contains("...") && width(m) <= 120, m);
        assertEquals("", TextFit.clip(TerminalLayoutTest::width, "", 5));
    }

    @Test void wrapBreaksLongWordsAndLimitAddsEllipsis() {
        List<String> lines = TextFit.wrap(TerminalLayoutTest::width, "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW rest", 60);
        for (String line : lines) assertTrue(width(line) <= 60, line);
        assertTrue(lines.size() >= 4);
        List<String> two = TextFit.wrapLimit(TerminalLayoutTest::width, "one two three four five six seven eight nine ten eleven twelve", 60, 2);
        assertEquals(2, two.size());
        assertTrue(two.get(1).endsWith("...") && width(two.get(1)) <= 60);
        assertEquals(List.of("a", "b"), TextFit.wrap(TerminalLayoutTest::width, "a\nb", 60));
    }

    @Test void tabBarFitsAtEnglishAndPseudoWidthsAndShrinksTheWidestFirst() throws IOException {
        Map<String, String> l = lang();
        for (boolean pseudo : new boolean[] {false, true}) {
            String[] labels = new String[TABS.length];
            for (int i = 0; i < TABS.length; i++) labels[i] = pseudo ? PseudoLoc.expand(l.get(TABS[i])) : l.get(TABS[i]);
            int[][] tabs = TerminalLayout.tabs(TerminalLayoutTest::width, labels);
            for (int i = 0; i < labels.length; i++) {
                assertTrue(tabs[0][i] >= 8, "tab " + i + " starts inside the panel margin");
                assertTrue(tabs[0][i] + tabs[1][i] <= TerminalLayout.W - 8, "tab " + i + " ends inside the panel (pseudo=" + pseudo + ")");
                if (i > 0) assertTrue(tabs[0][i] >= tabs[0][i - 1] + tabs[1][i - 1], "tabs do not overlap");
                if (!pseudo) assertTrue(width(labels[i]) <= tabs[1][i] - TerminalLayout.TAB_ICON_PAD - TerminalLayout.TEXT_PAD, "English tab label '" + labels[i] + "' is not cut");
            }
        }
    }

    @Test void headerKeepsTheOwnerNameClearOfTheTitleAndTheCloseButton() throws IOException {
        int titleW = width(lang().get("gui.nullspace.title"));
        assertTrue(TerminalLayout.ownerX(titleW) > TerminalLayout.TITLE_X + titleW, "owner starts after the title");
        assertTrue(width(OWNER) <= TerminalLayout.ownerWidth(titleW), "a 16-character owner fits beside the title");
        assertTrue(TerminalLayout.ownerX(titleW) + TerminalLayout.ownerWidth(titleW) < TerminalLayout.CLOSE_X);
    }

    @Test void guestRowHoldsAnyValidPlayerNameAndTheRegionsDoNotOverlap() {
        assertTrue(width(OWNER) <= TerminalLayout.GUEST_TEXT_W, "worst-case 16-character name: " + width(OWNER) + " in " + TerminalLayout.GUEST_TEXT_W);
        assertTrue(width(OWNER) + 8 <= TerminalLayout.NAME_BOX_W, "the invite name box shows a full name");
        assertTrue(TerminalLayout.GUEST_TEXT_X + TerminalLayout.GUEST_TEXT_W < TerminalLayout.PERM_X, "name column ends before the toggles");
        int lastPerm = TerminalLayout.PERM_X + (TerminalLayout.PERM_COUNT - 1) * TerminalLayout.PERM_STEP + TerminalLayout.PERM_W;
        assertTrue(lastPerm < TerminalLayout.REMOVE_X, "toggles end before Remove");
        assertEquals(TerminalLayout.W - TerminalLayout.MARGIN, TerminalLayout.REMOVE_X + TerminalLayout.REMOVE_W, "Remove ends at the right margin");
        assertTrue(TerminalLayout.KIND_X + TerminalLayout.KIND_W_TIMED + 4 <= TerminalLayout.HOURS_X && TerminalLayout.HOURS_X + TerminalLayout.HOURS_W + 2 <= TerminalLayout.INVITE_X, "kind, hours and invite do not overlap");
        assertTrue(TerminalLayout.NAME_BOX_W + TerminalLayout.MARGIN + 4 <= TerminalLayout.KIND_X, "name box ends before the kind button");
    }

    @Test void cardsViewRegionsDoNotOverlap() {
        assertTrue(TerminalLayout.MODE_GUESTS_X + TerminalLayout.MODE_GUESTS_W < TerminalLayout.MODE_CARDS_X, "the two mode buttons are apart");
        assertTrue(TerminalLayout.SPAN_W >= 60, "the page span has room to the right of the switch: " + TerminalLayout.SPAN_W);
        assertTrue(TerminalLayout.SPAN_RIGHT < 252, "the span ends before the paging buttons (x 252)");
        assertTrue(TerminalLayout.CARD_KIND_X + TerminalLayout.CARD_KIND_W + 4 <= TerminalLayout.CARD_HOURS_X, "kind and hours");
        assertTrue(TerminalLayout.CARD_HOURS_X + TerminalLayout.CARD_HOURS_W + 4 <= TerminalLayout.CARD_BIND_X_TIMED, "hours and binding");
        assertTrue(TerminalLayout.CARD_KIND_X + TerminalLayout.CARD_KIND_W + 4 <= TerminalLayout.CARD_BIND_X, "kind and binding without hours");
        assertTrue(TerminalLayout.CARD_BIND_RIGHT + 4 <= TerminalLayout.CARD_PRINT_X, "binding and Print");
        assertEquals(TerminalLayout.W - TerminalLayout.MARGIN, TerminalLayout.CARD_PRINT_X + TerminalLayout.CARD_PRINT_W, "Print ends at the right margin");
        assertTrue(TerminalLayout.cardBindW(true) >= 60 && TerminalLayout.cardBindW(false) > TerminalLayout.cardBindW(true));
    }

    // the team row title keeps its prefix and shows as much of the party name as fits, whatever the name, language or
    // font
    @Test void teamRowTitleKeepsThePrefixAndNeverOverflowsForAnyPartyName() throws IOException {
        Map<String, String> l = lang();
        for (boolean pseudo : new boolean[] {false, true}) {
            String pattern = l.get("gui.nullspace.team.title"), solo = l.get("gui.nullspace.team.title.none");
            if (pseudo) { pattern = PseudoLoc.expand(pattern); solo = PseudoLoc.expand(solo); }
            String prefix = String.format(pattern, "").stripTrailing();
            for (String name : new String[] {"A", "Night Shift", "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW", "iiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii", "odd % %s name", "x".repeat(200)}) {
                String shown = TerminalLayout.teamTitle(TerminalLayoutTest::width, pattern, solo, name);
                assertTrue(width(shown) <= TerminalLayout.GUEST_TEXT_W, "pseudo=" + pseudo + " '" + shown + "' is " + width(shown));
                if (!pseudo) assertTrue(shown.startsWith(prefix), shown);
                if (!pseudo && (name.equals("A") || name.equals("Night Shift"))) assertEquals(String.format(pattern, name), shown, "a short name is shown whole");
            }
            assertTrue(width(TerminalLayout.teamTitle(TerminalLayoutTest::width, pattern, solo, "")) <= TerminalLayout.GUEST_TEXT_W);
            assertTrue(width(TerminalLayout.teamTitle(TerminalLayoutTest::width, pattern, solo, null)) <= TerminalLayout.GUEST_TEXT_W);
        }
        assertTrue(TerminalLayout.teamTitle(TerminalLayoutTest::width, "Team: %1$s", "Team: no party", "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW").endsWith("..."));
    }

    @Test void teamRowReusesTheGuestRowGeometry() {
        // the pinned row uses the same dot, text column, toggles and right-hand button as a guest row, so nothing new can overlap
        assertTrue(TerminalLayout.GUEST_TEXT_X + TerminalLayout.GUEST_TEXT_W < TerminalLayout.PERM_X);
        assertTrue(TerminalLayout.PERM_X + (TerminalLayout.PERM_COUNT - 1) * TerminalLayout.PERM_STEP + TerminalLayout.PERM_W < TerminalLayout.REMOVE_X);
        assertEquals(TerminalLayout.W - TerminalLayout.MARGIN, TerminalLayout.REMOVE_X + TerminalLayout.REMOVE_W);
    }

    @Test void destinationColumnsFitThePanelAndWorstCaseDataFits() {
        assertTrue(TerminalLayout.DEST_LIST_X + TerminalLayout.DEST_LIST_W < TerminalLayout.DEST_DETAIL_X, "list and detail do not overlap");
        assertTrue(TerminalLayout.DEST_DETAIL_X + TerminalLayout.DEST_DETAIL_W <= TerminalLayout.W - 6, "detail pane inside the panel");
        assertTrue(TerminalLayout.DEST_PANE_X + TerminalLayout.DEST_PANE_W <= TerminalLayout.DEST_DETAIL_X + TerminalLayout.DEST_DETAIL_W - 2, "pane content inside the detail inset");
        String id = "mymod:very_long_dimension_name_that_goes_on_and_on_and_on_and_on_and_on_and_on_and_on_and_on_and_on_and_on_1234567";
        String shown = TextFit.clipMiddle(TerminalLayoutTest::width, id, TerminalLayout.DEST_PANE_W);
        assertTrue(width(shown) <= TerminalLayout.DEST_PANE_W && shown.startsWith("mymod:") && shown.endsWith("1234567"), shown);
        assertEquals("-29999984 -64 29999984", TerminalLayout.coords(-29999984, -64, 29999984));
    }

    @Test void statusLineNeverDrawsMoreThanTwoLinesAndKnowsWhichMessagesAreCut() throws IOException {
        int cut = 0, total = 0;
        for (Map.Entry<String, String> e : lang().entrySet()) {
            String k = e.getKey();
            if (!(k.startsWith("error.") || k.startsWith("message.")) || k.contains(".preview.")) continue;
            String text = fmt(e.getValue(), "Bartholomew_Pike", "12", "9", "2 x Diamond, 1 x Chronon Cell", "north", "7");
            for (String variant : new String[] {text, PseudoLoc.expand(e.getValue())}) {
                List<String> lines = TextFit.wrapLimit(TerminalLayoutTest::width, fmt(variant, "Bartholomew_Pike", "12", "9", "2 x Diamond", "north", "7"), TerminalLayout.STATUS_TEXT_W, TerminalLayout.STATUS_LINES);
                assertTrue(lines.size() <= TerminalLayout.STATUS_LINES, k);
                for (String line : lines) assertTrue(width(line) <= TerminalLayout.STATUS_TEXT_W, k + ": " + line);
            }
            total++;
            if (TextFit.wrap(TerminalLayoutTest::width, text, TerminalLayout.STATUS_TEXT_W).size() > TerminalLayout.STATUS_LINES) cut++;
        }
        System.out.println("status line: " + cut + " of " + total + " error/message strings need the hover tooltip in English");
        assertTrue(total > 100);
    }
}
