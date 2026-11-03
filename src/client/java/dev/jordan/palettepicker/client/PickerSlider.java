package dev.jordan.palettepicker.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * Flat whole-number slider (floor and shape sizes). Dragging updates the value live without rebuilding the screen, so the
 * drag isn't lost; the scroll wheel and arrow keys step by one. The screen draws the value next to it.
 */
final class PickerSlider extends AbstractSliderButton {
    private final int min, max;
    private final IntConsumer onChange;
    private final Runnable onDone;

    /** @param onChange every new value, mid-drag included; @param onDone after a drag or step, to save and rebuild */
    PickerSlider(int x, int y, int w, int h, int min, int max, int value, IntConsumer onChange, Runnable onDone) {
        super(x, y, w, h, Component.empty(), (Math.max(min, Math.min(max, value)) - min) / (double) (max - min));
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        this.onDone = onDone;
        updateMessage();
    }

    int intValue() { return min + (int) Math.round(value * (max - min)); }

    @Override
    protected void updateMessage() { setMessage(Component.literal(String.valueOf(intValue()))); }

    @Override
    protected void applyValue() { onChange.accept(intValue()); }

    /** Snaps the handle to whole numbers. */
    @Override
    protected void setValue(double v) { super.setValue(Math.round(Math.max(0, Math.min(1, v)) * (max - min)) / (double) (max - min)); }

    void step(int by) {
        int to = Math.max(min, Math.min(max, intValue() + by));
        if (to == intValue()) return;
        setValue((to - min) / (double) (max - min));
        onDone.run();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (scrollY != 0) step((int) Math.signum(scrollY));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == InputConstants.KEY_LEFT || e.key() == InputConstants.KEY_RIGHT) { step(e.key() == InputConstants.KEY_LEFT ? -1 : 1); return true; }
        return super.keyPressed(e);
    }

    @Override
    public void onRelease(MouseButtonEvent e) {
        super.onRelease(e);
        onDone.run();
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight(), handle = x + (int) Math.round(value * (w - 8));
        boolean hot = active && isHoveredOrFocused();
        Style.box(g, x, y + h / 2 - 2, w, 4, Style.TRACK, Style.SLOT_BORDER);
        g.fill(x + 1, y + h / 2 - 1, handle + 4, y + h / 2 + 1, Style.ACCENT);
        Style.box(g, handle, y, 8, h, hot ? Style.BUTTON_HOVER : Style.BUTTON, hot ? Style.ACCENT_BORDER : Style.BUTTON_BORDER);
    }
}
