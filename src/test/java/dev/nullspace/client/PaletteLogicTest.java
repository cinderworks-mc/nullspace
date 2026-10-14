package dev.nullspace.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PaletteLogicTest {
    // ivory green charcoal wood copper amber window
    private static final List<Boolean> ALL = List.of(false, false, false, false, false, false, true);

    @Test void blankAndNameAndAliasMatch() {
        assertTrue(PaletteLogic.matches("nullspace:copper", "", List.of()));
        assertTrue(PaletteLogic.matches("nullspace:copper", "  CO ", List.of()));
        assertFalse(PaletteLogic.matches("nullspace:copper", "wood", List.of()));
        assertTrue(PaletteLogic.matches("nullspace:wood", "oak", List.of("Oak Planks", "Bookshelf")));
        assertTrue(PaletteLogic.matches("nullspace:window", "glass", List.of("Glass", "Glass Pane")));
        assertFalse(PaletteLogic.matches("nullspace:amber", "oak", List.of("Honey Block")));
    }

    @Test void layoutGroupsGlassSeparately() {
        var cells = PaletteLogic.layout(ALL, 3);
        assertEquals(7, cells.size());
        assertEquals(new PaletteLogic.Cell(3, 0, 1, false), cells.get(3));
        assertEquals(new PaletteLogic.Cell(6, 0, 0, true), cells.get(6));
        assertEquals(2, PaletteLogic.paintRows(cells));
        assertEquals(1, PaletteLogic.glassRows(cells));
        var only = PaletteLogic.layout(List.of(true), 3);
        assertEquals(0, PaletteLogic.paintRows(only));
    }

    @Test void arrowKeysWalkTheGrid() {
        var cells = PaletteLogic.layout(ALL, 3);
        assertEquals(1, PaletteLogic.move(cells, 0, 1, 0));
        assertEquals(0, PaletteLogic.move(cells, 0, -1, 0));
        assertEquals(3, PaletteLogic.move(cells, 0, 0, 1));
        assertEquals(0, PaletteLogic.move(cells, 0, 0, -1), "top row stays");
        assertEquals(6, PaletteLogic.move(cells, 4, 0, 1), "down from the paints reaches the window");
        assertEquals(3, PaletteLogic.move(cells, 6, 0, -1), "up from the window lands on the nearest column of the last paint row");
        assertEquals(6, PaletteLogic.move(cells, 6, 0, 1));
        assertEquals(-1, PaletteLogic.move(List.of(), 0, 1, 0));
    }

    @Test void fitScaleShrinksOnlyBelowDialogSize() {
        assertEquals(1f, PaletteLogic.fitScale(640, 480, 240, 204));
        assertTrue(PaletteLogic.fitScale(150, 100, 240, 204) < 0.5f);
        assertEquals(1f, PaletteLogic.fitScale(244, 208, 240, 204));
    }
}
