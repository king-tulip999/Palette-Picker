package dev.jordan.palettepicker.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.jordan.palettepicker.core.BlockData.Block;
import dev.jordan.palettepicker.core.BlockData.Face;
import dev.jordan.palettepicker.core.Generator;
import dev.jordan.palettepicker.core.Generator.Palette;
import dev.jordan.palettepicker.core.Shapes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Step 3 (shape mode): build the shape layer by layer. Settings on the left; in the middle either the shape in 3D (drag to
 * turn it) built up to the current layer, or the current layer from above (the layer below shows faintly where this one is
 * empty); a front view with the layer marked and the materials on the right. Scroll, the arrow keys or [ ] change layer.
 */
public class ShapeScreen extends PickerScreen {
    /** Smallest layout: bigger than the other screens, the views need the room. Still 3x at 1080p and 2x at 768p. */
    private static final int MIN_SHAPE_W = 600, MIN_SHAPE_H = 330;
    private static final int COL_W = 150, HALF = (COL_W - GAP) / 2, VIEW_X = 156, MIN_SIDE_W = 150, MIN_SIDE_H = 130, ROW_SHAPE = 24, ROW_SIZE = 88, ROW_HEIGHT = 108,
            ROW_HOLLOW = 132, ROW_FILL = 176, INFO_Y = 198, SLIDER_W = 84,
            LIST_ROW = 10, TEXTURE_MIN = 6;
    /** Big shapes take a moment to build: while a slider is dragged, re-render at most this often. */
    private static final long SLOW_MS = 40, THROTTLE_MS = 150;

    private final PickerState st;
    private final Palette palette;
    private final Map<String, BlockItems.Face> faces = new HashMap<>();
    private final ShapeView view3d = new ShapeView();
    /** The shape on screen and what's derived from it; it can lag the settings briefly while a size slider is dragged. */
    private String[][][] cells;
    private Object shownKey;
    private long renderMs, renderedAt;
    private Map<String, Integer> total = Map.of();
    /** Front view: the nearest block along each row, {@code [layer][column]}. */
    private String[][] front;
    private boolean countLayer, turning;
    private double lastX, lastY;
    private String toast = "";
    private long toastUntil;
    private int buttonsX;
    private PickerButton vary;

    public ShapeScreen(PickerState st) {
        super(Component.literal("Shape layers"), MIN_SHAPE_W, MIN_SHAPE_H);
        this.st = st;
        this.palette = st.generate();
        st.shapeLayer = Integer.MAX_VALUE; // open on the top layer: the whole shape
    }

    private int layers() { return cells.length; }

    // The view is square, as tall as the window allows while the right column keeps MIN_SIDE_W; that column takes the rest.
    private int view() { return Math.min(H - 22 - 26, W - VIEW_X - 6 - MIN_SIDE_W); }
    private int sideX0() { return VIEW_X + view() + 6; }
    private int sideW() { return W - sideX0(); }
    /** Front view: grows with the column, keeping room under it for the layer controls and about 8 material rows. */
    private int sideH() { return Math.max(MIN_SIDE_H, Math.min(sideW() * 3 / 4, H - 22 - 60 - 26 - 8 * LIST_ROW)); }
    private int layerY() { return 22 + sideH() + 4; }
    private int countY() { return layerY() + 30; }
    private int listY() { return countY() + 16; }

    /** Re-renders when the settings changed, unless the last render was slow and recent (a slider mid-drag). */
    private void refresh(boolean force) {
        Object key = List.of(st.shapeSpec(), st.shapeFill, st.shapeShift, st.shapeSeed);
        if (key.equals(shownKey)) return;
        long now = System.currentTimeMillis();
        if (!force && cells != null && renderMs > SLOW_MS && now - renderedAt < THROTTLE_MS) return;
        shownKey = key;
        cells = st.shape(palette);
        renderMs = System.currentTimeMillis() - now;
        renderedAt = System.currentTimeMillis();
        total = Shapes.counts(cells);
        int d = cells[0].length;
        front = new String[cells.length][d];
        for (int y = 0; y < cells.length; y++)
            for (int x = 0; x < d; x++)
                for (int z = d - 1; z >= 0 && front[y][x] == null; z--) front[y][x] = cells[y][z][x];
        st.shapeLayer = Math.max(0, Math.min(layers() - 1, st.shapeLayer));
    }

    @Override
    protected void layout() {
        refresh(true);
        boolean creative = minecraft.player != null && minecraft.player.hasInfiniteMaterials();
        addRenderableWidget(PickerButton.of("< Back", left, top, 40, 16, b -> minecraft.setScreenAndShow(new PaletteScreen(st))));

        Shapes.Kind[] kinds = Shapes.Kind.values();
        // Three to a row, each as wide as its label needs plus an even share of what's left (Cylinder won't fit a plain third).
        int kx = 0, rowExtra = 0;
        for (int i = 0; i < kinds.length; i++) {
            Shapes.Kind k = kinds[i];
            if (i % 3 == 0) {
                int n = Math.min(3, kinds.length - i), used = (n - 1) * GAP;
                for (int j = i; j < i + n; j++) used += PickerButton.fit(kinds[j].label, 0);
                rowExtra = (COL_W - used) / n;
                kx = 0;
            }
            int w = i % 3 == 2 || i == kinds.length - 1 ? COL_W - kx : PickerButton.fit(k.label, 0) + rowExtra;
            int x = kx;
            kx += w + GAP;
            addRenderableWidget(toggle(k.label, st.shapeKind == k, left + x, top + ROW_SHAPE + (i / 3) * 20, w, () -> {
                boolean wasTop = st.shapeLayer == layers() - 1;
                st.shapeKind = k;
                if (wasTop) st.shapeLayer = Integer.MAX_VALUE; // stay on the top layer, so the 3D view shows the whole shape
            }));
        }
        addRenderableWidget(new PickerSlider(left + 40, top + ROW_SIZE, SLIDER_W, 16, Shapes.MIN_SIZE, Shapes.MAX_SIZE, st.shapeSize,
                v -> st.shapeSize = v, this::sliderDone));
        if (st.shapeKind.tall)
            addRenderableWidget(new PickerSlider(left + 40, top + ROW_HEIGHT, SLIDER_W, 16, 1, Shapes.MAX_SIZE, st.shapeHeight, v -> st.shapeHeight = v, this::sliderDone));
        addRenderableWidget(toggle("Filled", !st.shapeHollow, left, top + ROW_HOLLOW, HALF, () -> st.shapeHollow = false));
        addRenderableWidget(toggle("Hollow", st.shapeHollow, left + HALF + GAP, top + ROW_HOLLOW, HALF, () -> st.shapeHollow = true));
        if (st.shapeHollow) {
            addRenderableWidget(toggle("Thin walls", !st.shapeThick, left, top + ROW_HOLLOW + 20, HALF, () -> st.shapeThick = false));
            addRenderableWidget(toggle("Thick walls", st.shapeThick, left + HALF + GAP, top + ROW_HOLLOW + 20, HALF, () -> st.shapeThick = true));
        }
        PickerButton[] fill = new PickerButton[1];
        fill[0] = addRenderableWidget(PickerButton.dropdown(st.shapeFill.label, null, left + 40, top + ROW_FILL, COL_W - 40, 16, b -> {
            List<MenuItem> items = new ArrayList<>();
            for (Shapes.Fill f : Shapes.Fill.values()) items.add(new MenuItem(f.label, null, f == st.shapeFill, () -> { st.shapeFill = f; rebuildWidgets(); }));
            openMenu(fill[0], items);
        }));

        int half = (sideW() - GAP) / 2;
        PickerButton down = PickerButton.of("Down", left + sideX0(), top + layerY() + 10, half, 16, b -> stepLayer(-1));
        PickerButton up = PickerButton.of("Up", left + sideX0() + half + GAP, top + layerY() + 10, half, 16, b -> stepLayer(1));
        down.active = st.shapeLayer > 0;
        up.active = st.shapeLayer < layers() - 1;
        addRenderableWidget(down);
        addRenderableWidget(up);
        addRenderableWidget(PickerButton.of(countLayer ? "Counting: layer" : "Counting: all", left + sideX0(), top + countY(), sideW(), 12,
                b -> { countLayer = !countLayer; rebuildWidgets(); }));

        int viewW = PickerButton.fit("Layer", 40);
        addRenderableWidget(toggle("3D", st.shapeIn3d, left, top + H - 20, viewW, 20, () -> st.shapeIn3d = true));
        addRenderableWidget(toggle("Layer", !st.shapeIn3d, left + viewW + GAP, top + H - 20, viewW, 20, () -> st.shapeIn3d = false));

        int bx = left + W;
        bx -= 50;
        addRenderableWidget(PickerButton.primary("Done", bx, top + H - 20, 50, 20, b -> minecraft.setScreenAndShow(new PaletteScreen(st))));
        if (creative) {
            bx -= 90 + GAP;
            addRenderableWidget(PickerButton.of("Give to hotbar", bx, top + H - 20, 90, 20, b -> give()));
        }
        int swapW = PickerButton.fit("Swap colors", 60);
        bx -= swapW + GAP;
        addRenderableWidget(PickerButton.of("Swap colors", bx, top + H - 20, swapW, 20, b -> { st.shapeShift++; rebuildWidgets(); }));
        int varyW = PickerButton.fit("Vary", 40);
        bx -= varyW + GAP;
        vary = PickerButton.of("Vary", bx, top + H - 20, varyW, 20, b -> { st.shapeSeed++; rebuildWidgets(); });
        vary.active = st.shapeFill == Shapes.Fill.SPECKLE;
        addRenderableWidget(vary);
        buttonsX = bx;
        st.save();
    }

    private PickerButton toggle(String label, boolean on, int x, int y, int w, Runnable action) { return toggle(label, on, x, y, w, 16, action); }

    private PickerButton toggle(String label, boolean on, int x, int y, int w, int h, Runnable action) {
        return PickerButton.option(label, on, x, y, w, h, btn -> { action.run(); rebuildWidgets(); });
    }

    /** After a size drag: render the final size and rebuild (the layer buttons depend on the layer count). */
    private void sliderDone() {
        refresh(true);
        rebuildWidgets();
    }

    private void stepLayer(int by) {
        int to = Math.max(0, Math.min(layers() - 1, st.shapeLayer + by));
        if (to == st.shapeLayer) return;
        st.shapeLayer = to;
        rebuildWidgets();
    }

    private void give() {
        List<Block> blocks = new ArrayList<>();
        for (String id : total.keySet()) st.data.get(id).ifPresent(blocks::add);
        toast = BlockItems.giveCreative(minecraft, blocks);
        toastUntil = System.currentTimeMillis() + 3500;
    }

    private int color(String id, Face face) { return 0xFF000000 | st.data.get(id).map(b -> b.color(face)).orElse(0x808080); }

    private String name(String id) { return st.data.get(id).map(Block::name).orElse(id); }

    private int size() { return cells[0].length; }
    // Layer view: the layer with its rulers (numbers above and to the left) centred in the view.
    private int viewCell() { return Math.max(1, Math.min(16, Math.min(view() - 4 - rulerW(size()), view() - 8 - RULER_H) / size())); }
    private int viewX() { return left + VIEW_X + (view() - rulerW(size()) - size() * viewCell()) / 2 + rulerW(size()); }
    private int viewY() { return top + 22 + (view() - RULER_H - size() * viewCell()) / 2 + RULER_H; }

    // Front view: layer numbers on the left.
    private int sideCell() { return Math.max(1, Math.min(16, Math.min((sideW() - 4 - rulerW(layers())) / size(), (sideH() - 4) / layers()))); }
    private int sideX() { return left + sideX0() + (sideW() - rulerW(layers()) - size() * sideCell()) / 2 + rulerW(layers()); }
    /** Top of the front view's bottom layer. */
    private int sideBottom() { return top + 22 + (sideH() + layers() * sideCell()) / 2 - sideCell(); }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        refresh(false);
        int d = size(), h = layers(), layer = Math.min(st.shapeLayer, h - 1);
        int sum = total.values().stream().mapToInt(Integer::intValue).sum();
        String mood = Generator.moodName(palette);
        label(g, mood, left + 46, top + 4, Style.HIGHLIGHT);
        label(g, " · " + d + "x" + h + "x" + d + " " + st.shapeKind.label.toLowerCase(Locale.ROOT) + " · " + String.format(Locale.ROOT, "%,d", sum) + " blocks",
                left + 46 + font.width(mood), top + 4, Style.TEXT);
        g.fill(left, top + 18, left + W, top + 19, Style.SURFACE_BORDER);

        label(g, "Size", left, top + ROW_SIZE + 4, Style.MUTED);
        label(g, String.valueOf(st.shapeSize), left + 40 + SLIDER_W + 6, top + ROW_SIZE + 4, Style.TEXT);
        label(g, "Height", left, top + ROW_HEIGHT + 4, Style.MUTED);
        if (st.shapeKind.tall) label(g, String.valueOf(st.shapeHeight), left + 40 + SLIDER_W + 6, top + ROW_HEIGHT + 4, Style.TEXT);
        else label(g, h + (h == 1 ? " layer" : " layers"), left + 40, top + ROW_HEIGHT + 4, Style.DIM);
        label(g, "Fill", left, top + ROW_FILL + 4, Style.MUTED);

        Style.box(g, left + VIEW_X, top + 22, view(), view(), Style.SLOT, Style.SLOT_BORDER);
        region("shape view", left + VIEW_X, top + 22, view(), view());
        String hovered = null;
        int hoverRow = 0, hoverCol = 0;
        if (st.shapeIn3d) view3d.draw(g, st, cells, layer, left + VIEW_X + 2, top + 24, view() - 4, view() - 4);
        else {
            int cell = viewCell(), vx = viewX(), vy = viewY();
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < d; x++) {
                    int px = vx + x * cell, py = vy + z * cell;
                    String id = cells[layer][z][x];
                    if (id == null) {
                        if (layer > 0 && cells[layer - 1][z][x] != null) g.fill(px, py, px + cell, py + cell, Style.HOVER); // the layer below
                        continue;
                    }
                    if (cell >= TEXTURE_MIN) BlockItems.face(g, faces.computeIfAbsent(id, i -> BlockItems.topFace(minecraft, i)), px, py, cell);
                    else g.fill(px, py, px + cell, py + cell, color(id, Face.TOP));
                    if (mouseX >= px && mouseX < px + cell && mouseY >= py && mouseY < py + cell) {
                        hovered = id; hoverRow = z; hoverCol = x;
                        if (cell >= 3) BlockItems.hover(g, px, py, cell);
                    }
                }
            }
            // Centre lines, to count from.
            int mid = vx + d * cell / 2, midY = vy + d * cell / 2;
            g.fill(mid, vy, mid + 1, vy + d * cell, 0x50FFFFFF);
            g.fill(vx, midY, vx + d * cell, midY + 1, 0x50FFFFFF);
            boolean over = mouseX >= vx && mouseX < vx + d * cell && mouseY >= vy && mouseY < vy + d * cell;
            ruler(g, true, vx, vy, d, cell, false, over ? (mouseX - vx) / cell : -1, left + VIEW_X + 1, left + VIEW_X + view() - 1);
            ruler(g, false, vx, vy, d, cell, false, over ? (mouseY - vy) / cell : -1, top + 23, top + 21 + view());
        }

        // Front view: every layer, the current one marked and the ones above it dimmed.
        Style.box(g, left + sideX0(), top + 22, sideW(), sideH(), Style.SLOT, Style.SLOT_BORDER);
        region("front view", left + sideX0(), top + 22, sideW(), sideH());
        int sc = sideCell(), sx = sideX(), bottom = sideBottom();
        for (int y = 0; y < h; y++) {
            int py = bottom - y * sc;
            for (int x = 0; x < d; x++) {
                if (front[y][x] == null) continue;
                g.fill(sx + x * sc, py, sx + (x + 1) * sc, py + sc, color(front[y][x], Face.SIDE));
                if (y > layer) g.fill(sx + x * sc, py, sx + (x + 1) * sc, py + sc, 0x80161915);
            }
        }
        int ly = bottom - layer * sc;
        g.fill(sx, ly - 1, left + sideX0() + sideW() - 1, ly, Style.ACCENT_BORDER); // from the grid's edge, clear of the numbers
        g.fill(sx, ly + sc, left + sideX0() + sideW() - 1, ly + sc + 1, Style.ACCENT_BORDER);
        ruler(g, false, sx, bottom - (h - 1) * sc, h, sc, true, h - 1 - layer, top + 23, top + 21 + sideH());
        Map<String, Integer> here = Shapes.counts(new String[][][]{cells[layer]});
        int inLayer = here.values().stream().mapToInt(Integer::intValue).sum();
        label(g, "Layer " + (layer + 1) + "/" + h + " · " + inLayer, left + sideX0(), top + layerY(), Style.TEXT);

        // Materials.
        Map<String, Integer> counts = countLayer ? here : total;
        region("materials", left + sideX0(), top + listY(), sideW(), H - 24 - listY());
        String hoveredRow = null;
        int ry = top + listY();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (ry + LIST_ROW > top + H - 24) break;
            String n = String.format(Locale.ROOT, "%,d", e.getValue());
            g.fill(left + sideX0(), ry, left + sideX0() + 7, ry + 7, color(e.getKey(), Face.SIDE));
            int nameW = sideW() - 10 - font.width(n) - 3;
            label(g, BlockItems.ellipsizeQuietly(font, name(e.getKey()), nameW), left + sideX0() + 10, ry, Style.TEXT);
            label(g, n, left + W - font.width(n), ry, Style.MUTED);
            if (mouseX >= left + sideX0() && mouseX < left + W && mouseY >= ry && mouseY < ry + LIST_ROW) hoveredRow = e.getKey();
            ry += LIST_ROW;
        }

        // Info: what's under the mouse, else what the fill does and how to use the view.
        String info;
        if (vary != null && !vary.active && mouseX >= vary.getX() && mouseX < vary.getX() + vary.getWidth() && mouseY >= vary.getY() && mouseY < vary.getY() + vary.getHeight())
            info = "Vary reshuffles the Speckle fill. " + st.shapeFill.label + " has no randomness; try Swap colors.";
        else if (hovered != null) info = name(hovered) + ", row " + (hoverRow + 1) + " column " + (hoverCol + 1) + ". " + here.get(hovered) + " in this layer, "
                + Shapes.amount(total.get(hovered)) + " in all.";
        else if (hoveredRow != null) info = name(hoveredRow) + ": " + Shapes.amount(counts.get(hoveredRow)) + (countLayer ? " in this layer." : " in the whole shape.");
        else info = st.shapeFill.about + (st.shapeIn3d ? " Drag the view to turn it; scroll to build it up layer by layer." : " Scroll or use the arrow keys to change layer.");
        paragraph(g, Component.literal(info), left, top + INFO_Y, COL_W, Style.TEXT);

        if (System.currentTimeMillis() < toastUntil) toast(g, toast, left, top + H, buttonsX - GAP - left);
    }

    private boolean overView(double x, double y) { return x >= left + VIEW_X && x < left + VIEW_X + view() && y >= top + 22 && y < top + 22 + view(); }

    private boolean overSide(double x, double y) { return x >= left + sideX0() && x < left + W && y >= top + 22 && y < top + 22 + sideH(); }

    @Override
    protected boolean clicked(MouseButtonEvent e) {
        if (e.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        if (st.shapeIn3d && overView(e.x(), e.y())) { turning = true; lastX = e.x(); lastY = e.y(); return true; }
        if (!overSide(e.x(), e.y())) return false;
        int y = (int) Math.floor((sideBottom() + sideCell() - e.y()) / sideCell());
        stepLayer(Math.max(0, Math.min(layers() - 1, y)) - st.shapeLayer);
        return true;
    }

    @Override
    protected boolean dragged(MouseButtonEvent e) {
        if (!turning) return false;
        view3d.turn(e.x() - lastX, e.y() - lastY);
        lastX = e.x();
        lastY = e.y();
        return true;
    }

    @Override
    protected void released() { turning = false; }

    @Override
    protected boolean scrolled(double mouseX, double mouseY, double scrollY) {
        if (!(overView(mouseX, mouseY) || overSide(mouseX, mouseY)) || scrollY == 0) return false;
        stepLayer((int) Math.signum(scrollY));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (!menuOpen() && !(getFocused() instanceof PickerSlider)) {
            if (e.key() == InputConstants.KEY_UP || e.key() == InputConstants.KEY_RBRACKET) { stepLayer(1); return true; }
            if (e.key() == InputConstants.KEY_DOWN || e.key() == InputConstants.KEY_LBRACKET) { stepLayer(-1); return true; }
        }
        return super.keyPressed(e);
    }

    void auditLayer(int layer) { st.shapeLayer = layer; }
    void auditTurn(double degrees) { view3d.yaw = Math.toRadians(degrees); }
    void auditToast() { toast = "Gave 6 blocks to the hotbar, 14 stairs/slabs/walls to inventory (3 items moved). Inventory full, 1 skipped."; toastUntil = Long.MAX_VALUE; }

    @Override
    public void onClose() {
        st.save();
        super.onClose();
    }
}
