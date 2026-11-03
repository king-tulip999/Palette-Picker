package dev.jordan.palettepicker.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Flat button in the picker's {@link Style}: charcoal, green when it's the current choice or the main action. */
final class PickerButton extends Button {
    /** Room a {@link #dropdown} caret takes on the right. */
    static final int CARET_W = 10;

    private final boolean green;
    private final int textLeft; // > 0: left-align text at this offset instead of centering
    private boolean caret;
    private String icon;

    private PickerButton(int x, int y, int w, int h, Component label, OnPress onPress, boolean green, int textLeft) {
        super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
        this.green = green;
        this.textLeft = textLeft;
    }

    static PickerButton of(String label, int x, int y, int w, int h, OnPress onPress) { return new PickerButton(x, y, w, h, Component.literal(label), onPress, false, 0); }

    /** The screen's main action (Generate, Give). */
    static PickerButton primary(String label, int x, int y, int w, int h, OnPress onPress) { return new PickerButton(x, y, w, h, Component.literal(label), onPress, true, 0); }

    /** The current choice in a row of options; pressing it again does nothing. */
    static PickerButton option(String label, boolean selected, int x, int y, int w, int h, OnPress onPress) {
        PickerButton b = new PickerButton(x, y, w, h, Component.literal(label), onPress, selected, 0);
        if (selected) b.active = false;
        return b;
    }

    /** Opens a menu: left-aligned text with a caret on the right, and an optional block icon before the text. */
    static PickerButton dropdown(String label, String iconBlockId, int x, int y, int w, int h, OnPress onPress) {
        PickerButton b = new PickerButton(x, y, w, h, Component.literal(label), onPress, false, iconBlockId != null ? 17 : 5);
        b.caret = true;
        b.icon = iconBlockId;
        return b;
    }

    /** Width that fits {@code label} without cutting it off, at least {@code min}. */
    static int fit(String label, int min) { return Math.max(min, Minecraft.getInstance().font.width(label) + 10); }

    /** Width of a {@link #dropdown} that fits {@code label} (and an icon, if it has one). */
    static int fitDropdown(String label, boolean icon) { return Minecraft.getInstance().font.width(label) + (icon ? 17 : 5) + 4 + CARET_W; }

    static PickerButton leftAligned(String label, int textLeft, int x, int y, int w, int h, OnPress onPress) { return new PickerButton(x, y, w, h, Component.literal(label), onPress, false, textLeft); }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        boolean hot = active && isHoveredOrFocused();
        int fill = green ? (hot ? Style.ACCENT_HOVER : Style.ACCENT) : hot ? Style.BUTTON_HOVER : Style.BUTTON;
        Style.box(g, getX(), getY(), getWidth(), getHeight(), fill, green ? Style.ACCENT_BORDER : Style.BUTTON_BORDER);

        Font font = Minecraft.getInstance().font;
        int pad = textLeft > 0 ? textLeft : 4, right = caret ? CARET_W : 4;
        String label = getMessage().getString();
        // Left-aligned buttons (saved list, dropdowns) hold names that are meant to be shortened.
        String text = textLeft > 0 ? BlockItems.ellipsizeQuietly(font, label, getWidth() - pad - right) : BlockItems.ellipsize(font, label, getWidth() - pad - 4);
        int tx = textLeft > 0 ? getX() + textLeft : getX() + (getWidth() - font.width(text)) / 2;
        // Disabled and not the current choice: dim, so it doesn't look clickable.
        int color = green || hot ? 0xFFFFFFFF : active ? Style.TEXT : Style.DIM;
        g.text(font, text, tx, getY() + (getHeight() - 8) / 2, color, false);
        if (icon != null) {
            int s = Math.min(12, getHeight() - 4);
            if (!BlockItems.cube(g, icon, getX() + 3, getY() + (getHeight() - s) / 2f, s)) {
                g.pose().pushMatrix();
                g.pose().translate(getX() + 3, getY() + (getHeight() - s) / 2f);
                g.pose().scale(s / 16f, s / 16f);
                g.item(BlockItems.stack(icon, 1), 0, 0);
                g.pose().popMatrix();
            }
        }
        if (caret) { // small down-pointing triangle
            int cx = getX() + getWidth() - 7, cy = getY() + getHeight() / 2 - 1;
            for (int i = 0; i < 3; i++) g.fill(cx - 2 + i, cy + i, cx + 3 - i, cy + i + 1, color);
        }
    }
}
