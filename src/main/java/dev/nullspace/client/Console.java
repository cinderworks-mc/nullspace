package dev.nullspace.client;

import dev.nullspace.Nullspace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class Console {
    private Console() {}

    static final int PANEL = 0xF21C1814, BAND = 0xFF2B2219, INSET = 0xFF14110E, INSET_EDGE = 0xFF3A3026, OUTLINE = 0xFF0C0A08;
    static final int EDGE = 0xFFB87333, AMBER = 0xFFF2B24A, IVORY = 0xFFEFE6CC, DIM = 0xFF9C9582, FAINT = 0xFF6F6858;
    static final int GOOD = 0xFF8FC27A, BAD = 0xFFE07A5F;

    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, OUTLINE);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, EDGE);
        g.fill(x, y, x + w, y + h, PANEL);
    }

    static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, INSET_EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, INSET);
    }

    static ResourceLocation sprite(String name) { return ResourceLocation.fromNamespaceAndPath(Nullspace.ID, name); }

    static ResourceLocation swatch(String finishId) {
        String name = finishId.substring(finishId.indexOf(':') + 1);
        return ResourceLocation.fromNamespaceAndPath(Nullspace.ID, "textures/block/" + (name.equals("window") ? "shell_window" : "shell_" + name) + ".png");
    }

    static void drawSwatch(GuiGraphics g, String finishId, int x, int y, int size) {
        // every swatch texture is a plain 16x16 tile (the window's is its frame ring around a painted piece of sky; the model only samples the ring)
        g.blit(swatch(finishId), x, y, size, size, 0, 0, 16, 16, 16, 16);
    }

    static String clip(String text, int width) {
        var font = Minecraft.getInstance().font;
        return TextFit.clip(font::width, text, width);
    }

    static String clipMiddle(String text, int width) {
        var font = Minecraft.getInstance().font;
        return TextFit.clipMiddle(font::width, text, width);
    }

    // text field with an amber edge when focused. the vanilla widget inside is borderless so its text, hint and
    // suggestions keep working; a click anywhere on the outer box focuses it
    static final class Field extends EditBox {
        private final int ox, oy, ow, oh;

        Field(Font font, int x, int y, int w, int h, Component name) {
            super(font, x + 4, y + (h - 8) / 2, w - 8, 8, name);
            ox = x; oy = y; ow = w; oh = h;
            setBordered(false);
            setTextColor(IVORY);
            setTextColorUneditable(FAINT);
        }

        @Override public boolean isMouseOver(double mx, double my) { return active && visible && mx >= ox && mx < ox + ow && my >= oy && my < oy + oh; }
        @Override protected boolean clicked(double mx, double my) { return isMouseOver(mx, my); }

        @Override public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            g.fill(ox, oy, ox + ow, oy + oh, isFocused() ? AMBER : 0xFF5E4A33);
            g.fill(ox + 1, oy + 1, ox + ow - 1, oy + oh - 1, INSET);
            super.renderWidget(g, mx, my, pt);
        }
    }

    static final class Btn extends AbstractButton {
        enum Style { NORMAL, ACCENT, DANGER }
        final String id;
        private final Runnable action;
        Style style = Style.NORMAL;
        boolean selected, lit, locked, forbidden;
        String sprite, swatchFinish;
        boolean left;
        int padLeft, padRight;
        private boolean autoTip;

        Btn(String id, int x, int y, int w, int h, Component text, Runnable action) {
            super(x, y, w, h, text);
            this.id = id; this.action = action;
        }

        Btn style(Style s) { style = s; return this; }
        Btn selected(boolean s) { selected = s; return this; }
        Btn icon(String sprite, boolean lit) { this.sprite = sprite; this.lit = lit; return this; }
        Btn swatch(String finish) { swatchFinish = finish; return this; }
        Btn leftAligned() { left = true; return this; }
        Btn pad(int leftPx, int rightPx) { padLeft = leftPx; padRight = rightPx; return this; }
        Btn enabled(boolean e) { active = e; return this; }

        @Override public void onPress() { action.run(); }

        @Override public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            var font = Minecraft.getInstance().font;
            boolean hot = isHoveredOrFocused() && active;
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int fill, edge, ink;
            if (sprite != null) {
                fill = lit ? 0xFF5A4121 : 0xFF1F1B16;
                edge = forbidden ? 0xFF5A2E26 : lit ? (hot ? 0xFFFFD27A : AMBER) : hot ? EDGE : 0xFF3A3026;
                g.fill(x, y, x + w, y + h, edge);
                g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
                int ix = x + (w - 16) / 2, iy = y + (h - 16) / 2;
                g.setColor(1f, 1f, 1f, forbidden ? 0.18f : lit ? 1f : 0.32f);
                g.blitSprite(sprite(sprite), ix, iy, 16, 16);
                g.setColor(1f, 1f, 1f, 1f);
                if (lit && !forbidden) { g.fill(x + w - 4, y + 2, x + w - 2, y + 4, locked ? AMBER : GOOD); }
                if (forbidden) for (int i = 0; i < Math.min(w, h) - 2; i++) g.fill(x + 1 + i, y + h - 2 - i, x + 2 + i, y + h - 1 - i, BAD);
                return;
            }
            if (!active) { fill = 0xFF1F1B16; edge = 0xFF2E271F; ink = FAINT; }
            else if (selected) { fill = hot ? 0xFFFFC862 : AMBER; edge = 0xFFFFE0A0; ink = 0xFF1B1510; }
            else if (style == Style.ACCENT) { fill = hot ? 0xFF6B4A22 : 0xFF553B1C; edge = AMBER; ink = IVORY; }
            else if (style == Style.DANGER) { fill = hot ? 0xFF6A2E24 : 0xFF45231C; edge = 0xFFB5523B; ink = IVORY; }
            else { fill = hot ? 0xFF4A3B2A : 0xFF302922; edge = hot ? AMBER : 0xFF5E4A33; ink = IVORY; }
            g.fill(x, y, x + w, y + h, edge);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
            int sw = swatchFinish == null ? 0 : Math.min(h >= 20 ? 16 : 10, h - 4);
            int pad = 3 + (sw > 0 ? sw + 3 : 0) + padLeft;
            if (sw > 0) drawSwatch(g, swatchFinish, x + 3, y + (h - sw) / 2, sw);
            String full = getMessage().getString();
            String text = clip(full, w - pad - 3 - padRight);
            if (!text.equals(full) && !autoTip && getTooltip() == null) { setTooltip(Tooltip.create(getMessage())); autoTip = true; }
            int tx = left || sw > 0 || padLeft > 0 ? x + pad : x + padLeft + (w - padLeft - padRight - font.width(text)) / 2;
            g.drawString(font, text, tx, y + (h - 8) / 2, ink, false);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }
    }
}
