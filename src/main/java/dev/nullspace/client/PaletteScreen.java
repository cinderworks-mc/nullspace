package dev.nullspace.client;

import dev.nullspace.client.Console.Btn;
import dev.nullspace.client.Console.Field;
import dev.nullspace.network.SelectFinish;
import dev.nullspace.server.Finishes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.nullspace.client.Console.*;

public final class PaletteScreen extends Screen {
    private static final int W = 240, H = 216, COLS = 3, TILE = 28, STEP = 31, CAP = 10;
    private static final int GRID_X = 8, GRID_Y = 50, PREV_X = 108, PREV_W = 124, PREV_H = 114;
    private static final int HINT_LINES = 4;
    private final String selected;
    private EditBox search;
    private String query = "";
    private int left, top;
    private float scale = 1f;
    private int cursor = -2; // -2 = not yet placed; then an index into shown()
    private Map<String, List<ItemStack>> mapped;
    private List<String> shownCache;
    private List<PaletteLogic.Cell> cells = List.of();

    public static void open(String selected) { Minecraft.getInstance().setScreen(new PaletteScreen(selected)); }

    private PaletteScreen(String selected) { super(Component.translatable("gui.nullspace.palette.title")); this.selected = selected == null ? "" : selected; }

    private Map<String, List<ItemStack>> mapped() {
        if (mapped == null) {
            mapped = new HashMap<>();
            for (String id : Finishes.all()) {
                List<ItemStack> items = new ArrayList<>();
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("nullspace", "finish/" + id.substring(id.indexOf(':') + 1)));
                BuiltInRegistries.BLOCK.getTag(tag).ifPresent(set -> set.forEach(h -> { ItemStack s = new ItemStack(h.value()); if (!s.isEmpty()) items.add(s); }));
                mapped.put(id, items);
            }
        }
        return mapped;
    }

    private List<String> aliases(String id) { return mapped().getOrDefault(id, List.of()).stream().map(s -> s.getHoverName().getString()).toList(); }

    private static final List<String> WINDOW_WORDS = List.of("starfield", "glass", "stars");

    private List<String> shown() {
        if (shownCache == null) shownCache = Finishes.all().stream().filter(id -> {
            List<String> al = new ArrayList<>(aliases(id));
            if (id.equals(Finishes.WINDOW)) al.addAll(WINDOW_WORDS);
            return PaletteLogic.matches(id, query, al);
        }).toList();
        return shownCache;
    }

    @Override protected void init() {
        scale = PaletteLogic.fitScale(width, height, W, H);
        int vw = Math.round(width / scale), vh = Math.round(height / scale);
        left = (vw - W) / 2; top = Math.max(2, (vh - H) / 2);
        shownCache = null;
        add(new Btn("close", left + W - 22, top + 5, 14, 12, Component.literal("x"), this::onClose).style(Btn.Style.DANGER));
        search = new Field(font, left + 8, top + 28, W - 16, 16, Component.translatable("gui.nullspace.palette.search"));
        search.setHint(Component.literal(clip(Component.translatable("gui.nullspace.palette.search.hint").getString(), W - 24))); // i18n-ok: an already translated hint, cut to the box
        // EditBox only draws its hint while unfocused, and this box starts focused (empty black bar). A suggestion shows while the box is empty even when focused.
        if (query.isEmpty()) search.setSuggestion(clip(Component.translatable("gui.nullspace.palette.search.hint").getString(), W - 24));
        search.setMaxLength(24);
        search.setValue(query);
        search.setResponder(s -> { query = s; cursor = 0; rebuildWidgets(); search.setFocused(true); });
        addRenderableWidget(search);
        setInitialFocus(search);
        List<String> shown = shown();
        if (cursor == -2) cursor = Math.max(0, shown.indexOf(selected));
        cursor = shown.isEmpty() ? -1 : Math.min(cursor, shown.size() - 1);
        cells = PaletteLogic.layout(shown.stream().map(id -> id.equals(Finishes.WINDOW)).toList(), COLS);
        int paintRows = PaletteLogic.paintRows(cells);
        for (var c : cells) {
            String id = shown.get(c.index());
            int x = left + GRID_X + c.col() * STEP, y = tileY(c, paintRows);
            Tile t = new Tile("finish:" + id.substring(id.indexOf(':') + 1), x, y, id, c.index());
            t.setTooltip(Tooltip.create(TerminalScreen.finishTip(id, id.equals(selected))));
            addRenderableWidget(t);
        }
    }

    private int tileY(PaletteLogic.Cell c, int paintRows) {
        int y = top + GRID_Y;
        if (paintRows > 0) y += CAP;
        if (!c.glass()) return y + c.row() * STEP;
        return y + paintRows * STEP + 3 + CAP + c.row() * STEP;
    }

    private final class Tile extends AbstractButton {
        final String id, finish;
        final int index;

        Tile(String id, int x, int y, String finish, int index) {
            super(x, y, TILE, TILE, Finishes.name(finish));
            this.id = id; this.finish = finish; this.index = index;
        }

        @Override public void onPress() { choose(finish); }

        @Override public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            boolean sel = finish.equals(selected), hot = isHovered() || index == cursor;
            g.blitSprite(sprite(sel ? "swatch_tile_selected" : hot ? "swatch_tile_hover" : "swatch_tile"), getX(), getY(), TILE, TILE);
            drawSwatch(g, finish, getX() + 4, getY() + 4, 20);
            if (sel) g.blitSprite(sprite("confirm"), getX() + TILE - 11, getY() - 3, 12, 12);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }
    }

    private Btn add(Btn b) { addRenderableWidget(b); return b; }

    void choose(String id) {
        PacketDistributor.sendToServer(new SelectFinish(id));
        onClose();
    }

    boolean click(String id) {
        for (var c : children()) {
            if (c instanceof Btn b && b.id.equals(id)) return clickAt(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0);
            if (c instanceof Tile t && t.id.equals(id)) return clickAt(t.getX() + t.getWidth() / 2.0, t.getY() + t.getHeight() / 2.0);
        }
        return false;
    }

    private boolean clickAt(double vx, double vy) {
        boolean handled = mouseClicked(vx * scale, vy * scale, 0);
        mouseReleased(vx * scale, vy * scale, 0);
        return handled;
    }

    List<String> tileIds() { List<String> out = new ArrayList<>(); for (var c : children()) if (c instanceof Tile t) out.add(t.id); return out; }

    double[] tileCentre(String id) {
        for (var c : children()) if (c instanceof Tile t && t.id.equals(id)) return new double[] {(t.getX() + TILE / 2.0) * scale, (t.getY() + TILE / 2.0) * scale};
        return null;
    }

    int cursorIndex() { return cursor; }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        List<String> shown = shown();
        boolean typing = search.isFocused() && !query.isEmpty();
        int dx = key == 262 ? 1 : key == 263 ? -1 : 0, dy = key == 264 ? 1 : key == 265 ? -1 : 0;
        if ((dy != 0 || (dx != 0 && !typing)) && !shown.isEmpty()) { cursor = PaletteLogic.move(cells, cursor, dx, dy); return true; }
        if ((key == 257 || key == 335) && !shown.isEmpty() && cursor >= 0) { choose(shown.get(cursor)); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override public void render(GuiGraphics g, int rawMouseX, int rawMouseY, float partial) {
        g.pose().pushPose(); g.pose().scale(scale, scale, 1f);
        int mx = (int) (rawMouseX / scale), my = (int) (rawMouseY / scale);
        super.render(g, mx, my, partial);
        String hint = Component.translatable("gui.nullspace.palette.hint").getString();
        int hintY = top + GRID_Y + PREV_H + 4;
        if (mx >= left + 8 && mx < left + W - 8 && my >= hintY && my < hintY + HINT_LINES * 10 + 4 && TextFit.wrap(font::width, hint, W - 20).size() > HINT_LINES)
            g.renderTooltip(font, font.split(Component.literal(hint), 200), mx, my); // i18n-ok: the already translated hint, in full
        g.pose().popPose();
    }

    @Override public boolean mouseClicked(double mx, double my, int button) { return super.mouseClicked(mx / scale, my / scale, button); }
    @Override public boolean mouseReleased(double mx, double my, int button) { return super.mouseReleased(mx / scale, my / scale, button); }
    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) { return super.mouseDragged(mx / scale, my / scale, button, dx / scale, dy / scale); }
    @Override public void mouseMoved(double mx, double my) { super.mouseMoved(mx / scale, my / scale); }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.pose().pushPose(); g.pose().scale(1f / scale, 1f / scale, 1f); // the dimmed backdrop covers the real window, not the scaled panel space
        super.renderBackground(g, mouseX, mouseY, partial);
        g.pose().popPose();
        panel(g, left, top, W, H);
        g.fill(left, top, left + W, top + 21, BAND);
        g.fill(left, top + 21, left + W, top + 22, EDGE);
        g.blitSprite(sprite("palette"), left + 6, top + 3, 16, 16);
        g.drawString(font, Component.translatable("gui.nullspace.palette.title"), left + 26, top + 7, AMBER, false);
        List<String> shown = shown();
        if (shown.isEmpty()) g.drawCenteredString(font, clip(Component.translatable("gui.nullspace.palette.none", query).getString(), W - 20), left + W / 2, top + 80, DIM);
        int paintRows = PaletteLogic.paintRows(cells), glassRows = PaletteLogic.glassRows(cells);
        if (paintRows > 0) g.drawString(font, Component.translatable("gui.nullspace.palette.group.paints"), left + GRID_X, top + GRID_Y, DIM, false);
        if (glassRows > 0) g.drawString(font, Component.translatable("gui.nullspace.palette.group.glass"), left + GRID_X, top + GRID_Y + (paintRows > 0 ? CAP + paintRows * STEP + 3 : 0), DIM, false);
        int hover = -1;
        for (var c : children()) if (c instanceof Tile t && t.isMouseOver(mouseX, mouseY)) hover = t.index;
        int shownIndex = hover >= 0 ? hover : cursor >= 0 ? cursor : shown.indexOf(selected);
        if (!shown.isEmpty() && shownIndex >= 0) preview(g, shown.get(Math.min(shownIndex, shown.size() - 1)));
        int hintY = top + GRID_Y + PREV_H + 6;
        g.fill(left + 8, hintY - 3, left + W - 8, hintY - 2, INSET_EDGE);
        List<String> lines = TextFit.wrapLimit(font::width, Component.translatable("gui.nullspace.palette.hint").getString(), W - 20, HINT_LINES);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), left + 10, hintY + 2 + i * 10, i == 0 ? DIM : FAINT, false);
    }

    private void preview(GuiGraphics g, String id) {
        int px = left + PREV_X, py = top + GRID_Y;
        inset(g, px, py, PREV_W, PREV_H);
        g.blitSprite(sprite("swatch_tile"), px + 5, py + 5, 56, 56);
        drawSwatch(g, id, px + 9, py + 9, 48);
        boolean sel = id.equals(selected);
        g.drawString(font, clip(Finishes.name(id).getString(), PREV_W - 70), px + 66, py + 10, AMBER, false);
        List<FormattedCharSequence> state = font.split(Component.translatable(sel ? "gui.nullspace.palette.preview.selected" : "gui.nullspace.palette.preview.choose"), PREV_W - 70);
        for (int i = 0; i < state.size() && i < 4; i++) g.drawString(font, state.get(i), px + 66, py + 22 + i * 10, sel ? GOOD : DIM, false);
        List<ItemStack> items = mapped().getOrDefault(id, List.of());
        if (!items.isEmpty()) {
            g.drawString(font, clip(Component.translatable("gui.nullspace.palette.preview.matches").getString(), PREV_W - 10), px + 6, py + 68, DIM, false);
            int fit = (PREV_W - 12) / 17, shownItems = items.size() <= fit ? items.size() : fit - 1;
            for (int i = 0; i < shownItems; i++) g.renderItem(items.get(i), px + 6 + i * 17, py + 80);
            if (shownItems < items.size())
                g.drawString(font, clip(Component.translatable("gui.nullspace.palette.preview.more", items.size() - shownItems).getString(), PREV_W - 12), px + 6, py + 99, FAINT, false);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}
