package dev.jordan.palettepicker.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The picker's flat dark theme (from the first mockup): charcoal panels, thin borders, a green accent. ARGB. */
final class Style {
    private Style() {}

    static final int BACKDROP = 0xB0000000;
    static final int WINDOW = 0xF21B1E1A, WINDOW_BORDER = 0xFF3A4037;
    static final int SURFACE = 0xFF242822, SURFACE_BORDER = 0xFF353B33;
    static final int SLOT = 0xFF161915, SLOT_BORDER = 0xFF3A4037, HOVER = 0x30FFFFFF;
    static final int BUTTON = 0xFF2E332C, BUTTON_HOVER = 0xFF3A4137, BUTTON_BORDER = 0xFF454C42;
    static final int ACCENT = 0xFF4F8A2F, ACCENT_HOVER = 0xFF5FA23A, ACCENT_BORDER = 0xFF7CC152;
    static final int TEXT = 0xFFE8EAE6, MUTED = 0xFF9BA397, DIM = 0xFF6E766B;
    static final int GOOD = 0xFF8FD16A, BAD = 0xFFE5735C, HIGHLIGHT = 0xFFE8C95A;
    static final int TRACK = 0xFF161915, THUMB = 0xFF4A5247, THUMB_HOVER = 0xFF5E685A;

    /** Filled box with a 1px border and its corner pixels cut, which reads as slightly rounded at GUI scale. */
    static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int border) {
        g.fill(x + 1, y, x + w - 1, y + h, border);
        g.fill(x, y + 1, x + w, y + h - 1, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }
}
