package dev.nullspace.client;

import java.util.function.ToIntFunction;

// geometry of the terminal without minecraft types. TerminalScreen reads every region from here, and the layout test
// checks the real strings (and 40% longer pseudo localised ones) against the same widths, so a layout change cannot
// quietly break a label
final class TerminalLayout {
    private TerminalLayout() {}

    static final int W = 300, H = 226, MARGIN = 10, INNER = W - 2 * MARGIN;
    static final int GAP = 4, TEXT_PAD = 3;

    static final int TITLE_X = 10, CLOSE_W = 14, CLOSE_X = W - 22, HEADER_GAP = 8;
    static final int TAB_Y = 24, TAB_H = 16, TAB_ICON = 12, TAB_ICON_PAD = 3 + TAB_ICON + 3, TAB_GAP = 2, TAB_MIN = 40;

    static int ownerX(int titleWidth) { return TITLE_X + titleWidth + HEADER_GAP; }
    static int ownerWidth(int titleWidth) { return CLOSE_X - 4 - ownerX(titleWidth); }

    static int[][] tabs(ToIntFunction<String> width, String... labels) {
        int n = labels.length, avail = W - 2 * 8 - (n - 1) * TAB_GAP;
        int[] w = new int[n];
        int sum = 0;
        for (int i = 0; i < n; i++) { w[i] = Math.max(TAB_MIN, width.applyAsInt(labels[i]) + TAB_ICON_PAD + TEXT_PAD + 2); sum += w[i]; }
        while (sum > avail) {
            int widest = 0;
            for (int i = 1; i < n; i++) if (w[i] > w[widest]) widest = i;
            if (w[widest] <= 24) break;
            w[widest]--; sum--;
        }
        int[] x = new int[n];
        int at = 8 + Math.max(0, (avail - sum) / 2);
        for (int i = 0; i < n; i++) { x[i] = at; at += w[i] + TAB_GAP; }
        return new int[][] {x, w};
    }

    static final int ROOM_LIST_X = 10, ROOM_LIST_W = 94, ROOM_PANE_X = 112, ROOM_PANE_W = 178, ROOM_BTN_W = 88, ROOM_BTN_STEP = 90;
    static final int DIALOG_W = 182, DIALOG_TEXT_W = 166, DIALOG_TITLE_W = 174;
    static final int SURFACE_BTN_W = 58, SURFACE_STEP = 60;

    static final int GUEST_DOT_X = 12, GUEST_TEXT_X = 18, PERM_W = 20, PERM_STEP = 21, PERM_COUNT = 6;
    static final int REMOVE_W = 44, REMOVE_X = W - MARGIN - REMOVE_W;
    static final int PERM_X = REMOVE_X - 3 - PERM_COUNT * PERM_STEP + 1;
    static final int GUEST_TEXT_W = PERM_X - 2 - GUEST_TEXT_X;
    static final int NAME_BOX_W = 106, KIND_X = 120, KIND_W_TIMED = 66, HOURS_X = 190, HOURS_W = 48, INVITE_X = 240, INVITE_W = 50;
    static final int KIND_W = INVITE_X - 4 - KIND_X;
    static final int PRESET_X = 140, PRESET_W = W - MARGIN - PRESET_X, SAVE_X = KIND_X, SAVE_W = W - MARGIN - SAVE_X;

    static String teamTitle(ToIntFunction<String> width, String pattern, String solo, String name) {
        if (name == null || name.isEmpty()) return TextFit.clip(width, solo, GUEST_TEXT_W);
        String bare = String.format(pattern, "");
        String clipped = TextFit.clip(width, name, Math.max(0, GUEST_TEXT_W - width.applyAsInt(bare)));
        return TextFit.clip(width, String.format(pattern, clipped), GUEST_TEXT_W);
    }

    static final int MODE_Y = 44, MODE_H = 12, MODE_GUESTS_X = 10, MODE_GUESTS_W = 82, MODE_CARDS_X = 94, MODE_CARDS_W = 72;
    static final int SPAN_RIGHT = 248, SPAN_W = SPAN_RIGHT - (MODE_CARDS_X + MODE_CARDS_W + 4);
    static final int CARD_KIND_X = 10, CARD_KIND_W = 80, CARD_HOURS_X = 94, CARD_HOURS_W = 46, CARD_BIND_X_TIMED = 144, CARD_BIND_X = 94, CARD_BIND_RIGHT = 236,
        CARD_PRINT_X = 240, CARD_PRINT_W = 50;
    static int cardBindX(boolean timed) { return timed ? CARD_BIND_X_TIMED : CARD_BIND_X; }
    static int cardBindW(boolean timed) { return CARD_BIND_RIGHT - cardBindX(timed); }

    static final int SETTING_TEXT_X = 58, SETTING_RIGHT = W - 14, SETTING_TEXT_W = SETTING_RIGHT - SETTING_TEXT_X, SETTING_BTN_W = 40;
    static final int SETTING_LINE_Y = 153, SETTING_LINE_STEP = 12;
    static final int INSTALL_W = 64, INSTALL_X = W - 10 - INSTALL_W, MODULE_LINE_W = INSTALL_X - 8 - MARGIN, FULL_LINE_W = W - 2 * MARGIN;

    static final int DEST_LIST_X = 8, DEST_LIST_W = 140, DEST_ROW_X = 10, DEST_ROW_W = 136, DEST_ROW_ICON = 12, DEST_NAME_X = 18, DEST_BADGE_W = 14;
    static final int DEST_NAME_W = DEST_ROW_W - DEST_NAME_X - DEST_BADGE_W;
    static final int DEST_DETAIL_X = 150, DEST_DETAIL_W = 142, DEST_PANE_X = 154, DEST_PANE_W = 134, DEST_NAME_HEAD_W = DEST_PANE_W - 16;
    static final int DEST_EMPTY_W = DEST_LIST_W - 8, NOHALL_W = W - 40;

    static final int STATUS_Y = 200, STATUS_H = 22, STATUS_TEXT_W = W - 22, STATUS_LINES = 2;

    // no separators, so nine digit values still fit the detail pane
    static String coords(int x, int y, int z) { return x + " " + y + " " + z; }
}
