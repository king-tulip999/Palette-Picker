package dev.jordan.palettepicker.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Base for the picker screens: a blurred, darkened world behind a dark window that fills the game window. The pixel scale is
 * the largest whole one (at most 3) at which the screen's minimum layout fits, independent of the GUI scale setting; the
 * window then takes all the room there is at that scale ({@link #W} x {@link #H}), and screens stretch their views and lists
 * into it. Subclasses lay out in virtual coordinates; mouse events arrive already converted.
 */
abstract class PickerScreen extends Screen {
    /** The smallest layout most screens work at. */
    static final int MIN_W = 464, MIN_H = 258, MAX_PIXEL_SCALE = 3, MARGIN = 14, WINDOW_PAD = 8;
    /** Space between neighbouring buttons. */
    static final int GAP = 4;

    /** Virtual px -> GUI px. */
    private float scale = 1;
    protected int left, top;

    /** This screen's minimum layout: it sets the pixel scale. */
    private final int minW, minH;
    /** Layout size: the game window less a margin, at least the minimum. Set before {@link #layout()}. */
    protected int W, H;

    PickerScreen(Component title) { this(title, MIN_W, MIN_H); }

    PickerScreen(Component title, int minW, int minH) {
        super(title);
        this.minW = W = minW;
        this.minH = H = minH;
    }

    @Override
    protected final void init() {
        int fbW = minecraft.getWindow().getWidth(), fbH = minecraft.getWindow().getHeight();
        int p = MAX_PIXEL_SCALE;
        while (p > 1 && (fbW / p < minW + 2 * MARGIN || fbH / p < minH + 2 * MARGIN)) p--;
        float px = p;
        if (fbW / px < minW + 2 * MARGIN || fbH / px < minH + 2 * MARGIN) px = Math.min(fbW / (float) (minW + 2 * MARGIN), fbH / (float) (minH + 2 * MARGIN));
        scale = px / minecraft.getWindow().getGuiScale();
        boolean small = LayoutAudit.ENABLED && LayoutAudit.minimumSize; // the audit also checks the smallest layout
        W = small ? minW : Math.max(minW, vw() - 2 * MARGIN);
        H = small ? minH : Math.max(minH, vh() - 2 * MARGIN);
        left = (vw() - W) / 2;
        top = Math.max(MARGIN, (vh() - H) / 2);
        menu = null;
        BlockItems.clearCache();
        layout();
    }

    /** Add widgets, in virtual coordinates. */
    protected abstract void layout();

    /** Draw the screen, in virtual coordinates. Widgets are drawn first. */
    protected abstract void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta);

    protected int vw() { return (int) (width / scale); }
    protected int vh() { return (int) (height / scale); }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        extractBlurredBackground(g);
        g.fill(0, 0, width, height, Style.BACKDROP);
        minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public final void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int mx = (int) (mouseX / scale), my = (int) (mouseY / scale);
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        Style.box(g, left - WINDOW_PAD, top - WINDOW_PAD, W + 2 * WINDOW_PAD, H + 2 * WINDOW_PAD, Style.WINDOW, Style.WINDOW_BORDER);
        LayoutAudit audit = LayoutAudit.ENABLED ? new LayoutAudit() : null;
        LayoutAudit.current = audit;
        // While a menu is open, nothing under it reacts to the mouse.
        int ux = menu != null ? -1 : mx, uy = menu != null ? -1 : my;
        super.extractRenderState(g, ux, uy, delta);
        draw(g, ux, uy, delta);
        if (menu != null) drawMenu(g, mx, my);
        g.pose().popMatrix();
        if (audit != null) {
            for (var c : children())
                if (c instanceof AbstractWidget w && w.visible) audit.add(LayoutAudit.Kind.WIDGET, w.getMessage().getString(), w.getX(), w.getY(), w.getWidth(), w.getHeight());
            lastIssues = audit.check(left, top, W, H, GAP);
            LayoutAudit.current = null;
        }
    }

    /** Layout problems found in the last frame (layout audit only). */
    List<String> lastIssues;

    /** One line of text, no shadow. Use this (not g.text) so the layout audit sees it. */
    protected void label(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(font, s, x, y, color, false);
        if (LayoutAudit.current != null) LayoutAudit.current.add(LayoutAudit.Kind.TEXT, s, x, y, font.width(s), font.lineHeight - 1);
    }

    /** Word-wrapped text, no shadow; returns its height. */
    protected int paragraph(GuiGraphicsExtractor g, Component text, int x, int y, int width, int color) {
        g.textWithWordWrap(font, text, x, y, width, color, false);
        var lines = font.split(text, width);
        if (LayoutAudit.current != null) {
            int w = lines.stream().mapToInt(font::width).max().orElse(0);
            LayoutAudit.current.add(LayoutAudit.Kind.TEXT, text.getString(), x, y, w, lines.size() * font.lineHeight - 1);
        }
        return lines.size() * font.lineHeight;
    }

    /** A short message on its own card, floating over whatever is under it. Anchored by its bottom-left corner; returns its height. */
    protected int toast(GuiGraphicsExtractor g, String msg, int x, int bottom, int width) {
        Component t = Component.literal(msg);
        int lines = font.split(t, width - 8).size(), h = lines * font.lineHeight + 7, y = bottom - h;
        Style.box(g, x, y, width, h, Style.SURFACE, Style.ACCENT_BORDER);
        g.textWithWordWrap(font, t, x + 4, y + 4, width - 8, Style.HIGHLIGHT, false);
        if (LayoutAudit.current != null) LayoutAudit.current.add(LayoutAudit.Kind.OVERLAY, msg, x, y, width, h);
        return h;
    }

    /** Height a ruler takes above a grid. */
    static final int RULER_H = 12;

    /** Width a ruler takes left of a grid numbered up to n. */
    protected int rulerW(int n) { return font.width(String.valueOf(n)) + 4; }

    /**
     * Block numbers along one edge of a grid of n cells, to count a build out by: a tick per cell when cells are big enough,
     * longer ones every 5, the centre in the accent colour, and numbers as often as they fit. Across: above the grid's
     * top-left corner (x0, y0); otherwise left of it, numbered bottom-up when {@code fromEnd} (layers). {@code mark} (a cell
     * counted from the top/left, or -1) is highlighted. Numbers stay within [lo, hi) along the ruler.
     */
    protected void ruler(GuiGraphicsExtractor g, boolean across, int x0, int y0, int n, int cell, boolean fromEnd, int mark, int lo, int hi) {
        int major = cell * 5 >= 4 ? 5 : cell * 10 >= 4 ? 10 : 50;
        for (int p = 0; p < n; p++) {
            int num = fromEnd ? n - p : p + 1, len = num % major == 0 ? 2 : cell >= 4 ? 1 : 0;
            if (len > 0 && p != mark) rulerTick(g, across, x0, y0, p * cell + cell / 2, len, len == 2 ? Style.MUTED : Style.DIM);
        }
        rulerTick(g, across, x0, y0, n * cell / 2, 3, Style.ACCENT_BORDER);
        if (mark >= 0 && mark < n) rulerTick(g, across, x0, y0, mark * cell + cell / 2, 3, Style.HIGHLIGHT);

        // Numbers: the marked cell first, then every step-th, then the first and last if there's room. None touch.
        int need = across ? font.width(String.valueOf(n)) + 3 : font.lineHeight + 1, gap = across ? 3 : 1, step = 1;
        for (int s : new int[]{1, 2, 5, 10, 20, 25, 50, 100}) { step = s; if (s * cell >= need) break; }
        int markNum = mark < 0 || mark >= n ? -1 : fromEnd ? n - mark : mark + 1;
        Set<Integer> wanted = new LinkedHashSet<>();
        if (markNum > 0) wanted.add(markNum);
        for (int k = step; k <= n; k += step) wanted.add(k);
        wanted.add(1);
        wanted.add(n);
        List<int[]> kept = new ArrayList<>(); // {number, start, length} along the ruler
        for (int num : wanted) {
            String s = String.valueOf(num);
            int p = fromEnd ? n - num : num - 1, c = (across ? x0 : y0) + p * cell + cell / 2;
            int len = across ? font.width(s) : font.lineHeight - 1, start = Math.max(lo, Math.min(hi - len, across ? c - (len - 1) / 2 : c - 4));
            if (kept.stream().noneMatch(k -> start < k[1] + k[2] + gap && k[1] < start + len + gap)) kept.add(new int[]{num, start, len});
        }
        for (int[] k : kept) {
            String s = String.valueOf(k[0]);
            int color = k[0] == markNum ? Style.HIGHLIGHT : Style.MUTED;
            if (across) label(g, s, k[1], y0 - RULER_H, color);
            else label(g, s, x0 - 3 - font.width(s), k[1], color);
        }
    }

    private static void rulerTick(GuiGraphicsExtractor g, boolean across, int x0, int y0, int at, int len, int color) {
        if (across) g.fill(x0 + at, y0 - len, x0 + at + 1, y0, color);
        else g.fill(x0 - len, y0 + at, x0, y0 + at + 1, color);
    }

    /** An area that holds other things (grid, panel, slot row): what's inside it isn't an overlap. */
    protected void region(String what, int x, int y, int w, int h) {
        if (LayoutAudit.current != null) LayoutAudit.current.add(LayoutAudit.Kind.REGION, what, x, y, w, h);
    }

    // ---- Dropdown menu: one at a time, floats over everything, closes on any click or Escape ----

    /** A menu row; a null action makes it a muted heading. */
    record MenuItem(String label, String icon, boolean selected, Runnable action) {
        static MenuItem heading(String label) { return new MenuItem(label, null, false, null); }
    }

    static final int MENU_ROW = 14;
    private List<MenuItem> menu;
    private int menuX, menuY, menuW;

    /** Opens a menu under {@code anchor} (or above it if there's no room below), kept inside the window. */
    protected void openMenu(net.minecraft.client.gui.components.AbstractWidget anchor, List<MenuItem> items) {
        boolean icons = items.stream().anyMatch(i -> i.icon() != null);
        int w = anchor.getWidth();
        for (MenuItem i : items) w = Math.max(w, font.width(i.label()) + (icons ? 22 : 8) + 6);
        int h = items.size() * MENU_ROW + 2;
        menu = items;
        menuW = Math.min(w, W);
        menuX = Math.max(left, Math.min(anchor.getX(), left + W - menuW));
        menuY = anchor.getY() + anchor.getHeight() + 1;
        if (menuY + h > top + H) menuY = Math.max(top, anchor.getY() - 1 - h);
    }

    private void drawMenu(GuiGraphicsExtractor g, int mx, int my) {
        int h = menu.size() * MENU_ROW + 2;
        boolean icons = menu.stream().anyMatch(i -> i.icon() != null);
        g.fill(menuX + 2, menuY + 2, menuX + menuW + 2, menuY + h + 2, 0x60000000); // drop shadow
        Style.box(g, menuX, menuY, menuW, h, Style.SURFACE, Style.BUTTON_BORDER);
        for (int i = 0; i < menu.size(); i++) {
            MenuItem it = menu.get(i);
            int y = menuY + 1 + i * MENU_ROW;
            boolean over = it.action() != null && mx >= menuX && mx < menuX + menuW && my >= y && my < y + MENU_ROW;
            if (it.selected()) g.fill(menuX + 1, y, menuX + menuW - 1, y + MENU_ROW, Style.ACCENT);
            else if (over) g.fill(menuX + 1, y, menuX + menuW - 1, y + MENU_ROW, Style.BUTTON_HOVER);
            int tx = menuX + (icons ? 20 : 6);
            if (it.icon() != null && !BlockItems.cube(g, it.icon(), menuX + 5, y + 1, 12)) {
                g.pose().pushMatrix();
                g.pose().translate(menuX + 5, y + 1);
                g.pose().scale(0.75f, 0.75f);
                g.item(BlockItems.stack(it.icon(), 1), 0, 0);
                g.pose().popMatrix();
            }
            String text = BlockItems.ellipsizeQuietly(font, it.label(), menuX + menuW - 4 - tx);
            g.text(font, text, tx, y + 3, it.action() == null ? Style.MUTED : it.selected() || over ? 0xFFFFFFFF : Style.TEXT, false);
        }
        if (LayoutAudit.current != null) LayoutAudit.current.add(LayoutAudit.Kind.OVERLAY, "menu", menuX, menuY, menuW, h);
    }

    /** Handles a click while a menu is open: picks the row under it, or just closes the menu. */
    private boolean menuClicked(MouseButtonEvent e) {
        List<MenuItem> items = menu;
        menu = null;
        if (e.x() < menuX || e.x() >= menuX + menuW || e.y() < menuY + 1) return true;
        int i = (int) ((e.y() - menuY - 1) / MENU_ROW);
        if (i < items.size() && items.get(i).action() != null) items.get(i).action().run();
        return true;
    }

    boolean menuOpen() { return menu != null; }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (menu != null && e.key() == InputConstants.KEY_ESCAPE) { menu = null; return true; }
        return super.keyPressed(e);
    }

    private MouseButtonEvent toVirtual(MouseButtonEvent e) { return new MouseButtonEvent(e.x() / scale, e.y() / scale, e.buttonInfo()); }

    /** Left-click in virtual coordinates; return true to consume it before widgets see it. */
    protected boolean clicked(MouseButtonEvent e) { return false; }

    @Override
    public final boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        MouseButtonEvent v = toVirtual(e);
        if (menu != null) return menuClicked(v);
        return clicked(v) || super.mouseClicked(v, doubleClick);
    }

    /** Drag in virtual coordinates; return true to consume it. */
    protected boolean dragged(MouseButtonEvent e) { return false; }

    protected void released() {}

    @Override
    public final boolean mouseReleased(MouseButtonEvent e) {
        released();
        return super.mouseReleased(toVirtual(e));
    }

    @Override
    public final boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        MouseButtonEvent v = toVirtual(e);
        return dragged(v) || super.mouseDragged(v, dx / scale, dy / scale);
    }

    @Override
    public void mouseMoved(double x, double y) { super.mouseMoved(x / scale, y / scale); }

    /** Scroll in virtual coordinates. */
    protected boolean scrolled(double x, double y, double scrollY) { return false; }

    @Override
    public final boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (menu != null) return true;
        return scrolled(x / scale, y / scale, scrollY) || super.mouseScrolled(x / scale, y / scale, scrollX, scrollY);
    }

    static final int SCROLLBAR_W = 6;

    /** Flat track with a thumb sized to the visible share. Draws nothing if everything fits. */
    static void scrollbar(GuiGraphicsExtractor g, int x, int y, int h, int first, int visible, int total, boolean hot) {
        if (total <= visible) return;
        Style.box(g, x, y, SCROLLBAR_W, h, Style.TRACK, Style.SLOT_BORDER);
        int th = thumbHeight(h, visible, total), ty = y + (h - th) * first / (total - visible);
        Style.box(g, x + 1, ty + 1, SCROLLBAR_W - 2, th - 2, hot ? Style.THUMB_HOVER : Style.THUMB, hot ? Style.THUMB_HOVER : Style.THUMB);
    }

    /** First visible row when the thumb's centre is dragged to {@code mouseY}. */
    static int scrollAt(double mouseY, int y, int h, int visible, int total) {
        if (total <= visible) return 0;
        int th = thumbHeight(h, visible, total);
        double t = (mouseY - y - th / 2.0) / (h - th);
        return (int) Math.round(Math.max(0, Math.min(1, t)) * (total - visible));
    }

    private static int thumbHeight(int h, int visible, int total) { return Math.max(10, h * visible / total); }

    @Override
    public boolean isPauseScreen() { return false; }
}
