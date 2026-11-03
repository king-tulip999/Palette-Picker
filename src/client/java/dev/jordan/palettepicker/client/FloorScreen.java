package dev.jordan.palettepicker.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.jordan.palettepicker.core.BlockData.Block;
import dev.jordan.palettepicker.core.BlockData.Face;
import dev.jordan.palettepicker.core.Floors;
import dev.jordan.palettepicker.core.Generator;
import dev.jordan.palettepicker.core.Generator.Palette;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Step 3 (floor mode): pick a pattern. Big preview with shape and size on the left; every pattern on the right as a
 * thumbnail in the palette's own colors, grouped, scrollable. Swap colors / Vary / Border tweak the result.
 */
public class FloorScreen extends PickerScreen {
    // Tiles are a thumbnail plus a small margin; names shorten (the info line shows the whole name on hover).
    private static final int MIN_TILE_W = 52, GOOD_TILE_W = 62, GRID_MIN_W = 252, HEADER_H = 12, MIN_PREVIEW = 176,
            THUMB_N = 9, SLIDER_W = 124, TEXTURE_MIN = 4, GRID_Y = 52; // the info line above the grid gets 3 lines

    private final PickerState st;
    private final Palette palette;
    private final Map<String, BlockItems.Face> faces = new HashMap<>();
    /** Grid rows: a group header (patterns empty) or up to cols patterns of one group. */
    private record Row(String header, List<Floors.Pattern> patterns) {
        int height(int tileH) { return header != null ? HEADER_H : tileH; }
    }
    private final List<Row> rows = new ArrayList<>();
    /** Pattern columns the rows were built for; tiles and thumbnails grow when there's room. */
    private int cols, tileW = MIN_TILE_W, thumbCell = 3;
    private boolean scrollToEnd;
    private int scrollRow;
    private boolean dragging, scrolledToPattern;
    /** What the info line says: the hovered pattern or block, else the selected pattern. */
    private String info = "";
    private PickerButton vary;

    public FloorScreen(PickerState st) {
        super(Component.literal("Floor patterns"));
        this.st = st;
        this.palette = st.generate();
    }

    // The preview grows with the window's height (leaving the pattern grid at least GRID_MIN_W); the grid takes the rest.
    private int preview() { return Math.max(MIN_PREVIEW, Math.min(H - 82, W - 28 - GRID_MIN_W - SCROLLBAR_W - 2)); }
    private int colW() { return preview() + 20; }
    private int rowShape() { return 24 + preview() + 6; }
    private int rowSize() { return rowShape() + 20; }

    /** Groups as rows of up to {@code cols} patterns, rebuilt when the window changes how many fit. */
    private void buildRows(int cols) {
        if (cols == this.cols) return;
        Floors.Pattern first = rows.isEmpty() ? null : rows.get(Math.min(scrollRow + 1, rows.size() - 1)).patterns().stream().findFirst().orElse(null);
        this.cols = cols;
        rows.clear();
        for (String group : Floors.GROUPS) {
            List<Floors.Pattern> ps = Floors.PATTERNS.stream().filter(p -> p.group().equals(group)).toList();
            rows.add(new Row(group, List.of()));
            for (int i = 0; i < ps.size(); i += cols) rows.add(new Row(null, ps.subList(i, Math.min(ps.size(), i + cols))));
        }
        scrollRow = 0;
        for (int r = 0; first != null && r < rows.size(); r++) if (rows.get(r).patterns().contains(first)) { scrollRow = Math.max(0, r - 1); break; }
    }

    private int gridX() { return left + colW() + 8; }
    private int gridY() { return top + GRID_Y; }
    private int gridH() { return H - GRID_Y - 26; }
    private int tileH() { return THUMB_N * thumbCell + 17; }

    private int barX() { return gridX() + cols * tileW; }

    /** Rows from scrollRow that fit in the grid's height. */
    private int visibleRows() {
        int h = 0, n = 0;
        for (int r = scrollRow; r < rows.size() && h + rows.get(r).height(tileH()) <= gridH(); r++) { h += rows.get(r).height(tileH()); n++; }
        return n;
    }

    private int maxScroll() {
        int h = 0, r = rows.size();
        while (r > 0 && h + rows.get(r - 1).height(tileH()) <= gridH()) h += rows.get(--r).height(tileH());
        return r;
    }

    @Override
    protected void layout() {
        int room = left + W - gridX() - SCROLLBAR_W - 2;
        int c = Math.max(1, room / GOOD_TILE_W);
        tileW = Math.max(MIN_TILE_W, room / c);
        thumbCell = Math.max(3, Math.min(6, (tileW - 6) / THUMB_N));
        buildRows(c);
        scrollRow = scrollToEnd ? maxScroll() : Math.min(scrollRow, maxScroll());
        addRenderableWidget(PickerButton.of("< Back", left, top, 40, 16, b -> minecraft.setScreenAndShow(new PaletteScreen(st))));

        int sx = left + 40;
        for (Floors.Shape shape : Floors.Shape.values()) {
            int w = PickerButton.fit(shape.label, 40);
            addRenderableWidget(toggle(shape.label, st.floorShape == shape, sx, top + rowShape(), w, () -> st.floorShape = shape));
            sx += w + GAP;
        }
        addRenderableWidget(new PickerSlider(left + 40, top + rowSize(), SLIDER_W, 16, Floors.MIN_SIZE, Floors.MAX_SIZE, st.floorSize, v -> st.floorSize = v, st::save));

        int bx = left + W;
        bx -= 50;
        addRenderableWidget(PickerButton.primary("Done", bx, top + H - 20, 50, 20, b -> minecraft.setScreenAndShow(new PaletteScreen(st))));
        int varyW = PickerButton.fit("Vary", 40);
        bx -= varyW + GAP;
        vary = PickerButton.of("Vary", bx, top + H - 20, varyW, 20, b -> { st.floorSeed++; rebuildWidgets(); });
        vary.active = st.pattern().random(); // fixed patterns have nothing to vary; the info line says so on hover
        addRenderableWidget(vary);
        int swapW = PickerButton.fit("Swap colors", 60);
        bx -= swapW + GAP;
        addRenderableWidget(PickerButton.of("Swap colors", bx, top + H - 20, swapW, 20, b -> { st.floorShift++; rebuildWidgets(); }));
        String border = st.floorBorder ? "Border: on" : "Border: off";
        int borderW = PickerButton.fit("Border: off", 60);
        bx -= borderW + GAP;
        addRenderableWidget(PickerButton.of(border, bx, top + H - 20, borderW, 20, b -> { st.floorBorder = !st.floorBorder; rebuildWidgets(); }));

        if (!scrolledToPattern) { // open with the current pattern in view
            scrolledToPattern = true;
            for (int r = 0; r < rows.size(); r++)
                if (rows.get(r).patterns().contains(st.pattern())) { scrollRow = Math.max(0, Math.min(maxScroll(), r - 1)); break; }
        }
        st.save();
    }

    private PickerButton toggle(String label, boolean on, int x, int y, int w, Runnable action) {
        return PickerButton.option(label, on, x, y, w, 16, btn -> { action.run(); rebuildWidgets(); });
    }

    // The floor with its rulers (numbers above and to the left) centred in the preview box.
    private int previewCell() { return Math.max(1, Math.min(40, (preview() - RULER_H - 4) / st.floorSize)); }
    private int previewX() { return left + (colW() - rulerW(st.floorSize) - st.floorSize * previewCell()) / 2 + rulerW(st.floorSize); }
    private int previewY() { return top + 24 + (preview() - RULER_H - st.floorSize * previewCell()) / 2 + RULER_H; }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        String mood = Generator.moodName(palette);
        label(g, mood, left + 46, top + 4, Style.HIGHLIGHT);
        label(g, " · " + st.floorSize + "x" + st.floorSize + " " + st.floorShape.label.toLowerCase(Locale.ROOT) + " floor", left + 46 + font.width(mood), top + 4, Style.TEXT);
        g.fill(left, top + 18, left + W, top + 19, Style.SURFACE_BORDER);
        g.fill(left + colW() + 3, top + 22, left + colW() + 4, top + H, Style.SURFACE_BORDER);

        // Preview: real block faces.
        Style.box(g, left, top + 22, colW(), preview() + 4, Style.SLOT, Style.SLOT_BORDER);
        region("floor preview", left, top + 22, colW(), preview() + 4);
        String[][] floor = st.floor(palette);
        int cell = previewCell(), px = previewX(), py = previewY();
        String hoveredBlock = null;
        int hoverRow = -1, hoverCol = -1;
        for (int r = 0; r < floor.length; r++) {
            for (int c = 0; c < floor[r].length; c++) {
                if (floor[r][c] == null) continue;
                int x = px + c * cell, y = py + r * cell;
                if (cell >= TEXTURE_MIN) BlockItems.face(g, faces.computeIfAbsent(floor[r][c], id -> BlockItems.topFace(minecraft, id)), x, y, cell);
                else g.fill(x, y, x + cell, y + cell, 0xFF000000 | st.data.get(floor[r][c]).map(b -> b.color(Face.TOP)).orElse(0x808080));
                if (mouseX >= x && mouseX < x + cell && mouseY >= y && mouseY < y + cell) {
                    hoveredBlock = floor[r][c]; hoverRow = r; hoverCol = c;
                    if (cell >= 3) BlockItems.hover(g, x, y, cell);
                }
            }
        }
        int n = st.floorSize;
        ruler(g, true, px, py, n, cell, false, hoverCol, left + 1, left + colW() - 1);
        ruler(g, false, px, py, n, cell, false, hoverRow, top + 23, top + 25 + preview());
        label(g, "Shape", left, top + rowShape() + 4, Style.MUTED);
        label(g, "Size", left, top + rowSize() + 4, Style.MUTED);
        label(g, String.valueOf(st.floorSize), left + 40 + SLIDER_W + 6, top + rowSize() + 4, Style.TEXT);

        // Pattern grid.
        Floors.Pattern hovered = null;
        Block[] inks = Floors.inks(palette, st.floorShift);
        Block frame = palette.block("border") != null ? palette.block("border") : inks[Floors.SECOND];
        region("pattern grid", gridX() - 1, gridY() - 1, barX() + SCROLLBAR_W - gridX() + 2, gridH() + 2);
        int y = gridY(), shown = visibleRows();
        for (int r = scrollRow; r < scrollRow + shown; r++) {
            Row row = rows.get(r);
            if (row.header() != null) {
                label(g, row.header(), gridX() + 1, y + 2, Style.MUTED);
                g.fill(gridX() + font.width(row.header()) + 6, y + 6, gridX() + cols * tileW, y + 7, Style.SURFACE_BORDER);
            } else {
                for (int i = 0; i < row.patterns().size(); i++) {
                    Floors.Pattern pat = row.patterns().get(i);
                    int tx = gridX() + i * tileW, thumb = THUMB_N * thumbCell, thx = tx + (tileW - thumb) / 2, thy = y + 2;
                    boolean sel = pat == st.pattern(), over = mouseX >= tx && mouseX < tx + tileW && mouseY >= y && mouseY < y + tileH();
                    if (over) { hovered = pat; g.fill(tx + 1, y + 1, tx + tileW - 1, y + tileH() - 1, Style.HOVER); }
                    thumbnail(g, pat, inks, frame, thx, thy);
                    if (sel) BlockItems.ring(g, thx - 1, thy - 1, thumb + 2);
                    String name = BlockItems.ellipsizeQuietly(font, pat.name(), tileW - 4); // the info line shows the whole name on hover
                    label(g, name, tx + (tileW - font.width(name)) / 2, thy + thumb + 3, sel ? Style.GOOD : Style.TEXT);
                }
            }
            y += row.height(tileH());
        }
        scrollbar(g, barX(), gridY(), gridH(), scrollRow, shown, shown + maxScroll(), dragging || overBar(mouseX, mouseY));

        // Info line: what's under the mouse, else the chosen pattern.
        if (vary != null && mouseX >= vary.getX() && mouseX < vary.getX() + vary.getWidth() && mouseY >= vary.getY() && mouseY < vary.getY() + vary.getHeight()) {
            info = vary.active ? "Vary: a new random layout for " + st.pattern().name() + "."
                    : "Vary reshuffles random patterns (Plank run and the Natural group). " + st.pattern().name() + " is a fixed pattern; try Swap colors.";
        } else if (hoveredBlock != null) {
            String id = hoveredBlock;
            info = st.data.get(id).map(Block::name).orElse(id) + ", row " + (hoverRow + 1) + " column " + (hoverCol + 1) + ". "
                    + Floors.counts(floor).get(id) + " in this floor.";
        } else {
            Floors.Pattern p = hovered != null ? hovered : st.pattern();
            info = p.name() + ". " + p.about();
        }
        paragraph(g, Component.literal(info), gridX(), top + 22, W - colW() - 8, Style.TEXT);
    }

    /** Pattern as flat colors, THUMB_N x THUMB_N at thumbCell px, with the current shape and border. */
    private void thumbnail(GuiGraphicsExtractor g, Floors.Pattern pat, Block[] inks, Block frame, int x, int y) {
        int[][] l = Floors.layout(pat, st.floorShape, THUMB_N, st.floorBorder, st.floorSeed);
        g.fill(x, y, x + THUMB_N * thumbCell, y + THUMB_N * thumbCell, Style.SLOT);
        for (int r = 0; r < THUMB_N; r++) {
            for (int c = 0; c < THUMB_N; c++) {
                int v = l[r][c];
                if (v == Floors.OUT) continue;
                Block b = v == Floors.FRAME ? frame : inks[v];
                g.fill(x + c * thumbCell, y + r * thumbCell, x + (c + 1) * thumbCell, y + (r + 1) * thumbCell, 0xFF000000 | b.color(Face.TOP));
            }
        }
    }

    private boolean overBar(double x, double y) { return x >= barX() && x < barX() + SCROLLBAR_W && y >= gridY() && y < gridY() + gridH(); }

    private void dragTo(double mouseY) { scrollRow = scrollAt(mouseY, gridY(), gridH(), visibleRows(), visibleRows() + maxScroll()); scrollRow = Math.min(scrollRow, maxScroll()); }

    @Override
    protected boolean clicked(MouseButtonEvent e) {
        if (e.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        if (maxScroll() > 0 && overBar(e.x(), e.y())) {
            dragging = true;
            dragTo(e.y());
            return true;
        }
        int y = gridY();
        for (int r = scrollRow; r < scrollRow + visibleRows(); r++) {
            Row row = rows.get(r);
            if (row.header() == null && e.y() >= y && e.y() < y + tileH()) {
                int col = (int) Math.floor((e.x() - gridX()) / tileW);
                if (col >= 0 && col < row.patterns().size()) {
                    st.floorPattern = row.patterns().get(col).id();
                    BlockItems.playPlace(minecraft, palette.placeable().get(0).id());
                    rebuildWidgets();
                    return true;
                }
            }
            y += row.height(tileH());
        }
        return false;
    }

    @Override
    protected boolean dragged(MouseButtonEvent e) {
        if (!dragging) return false;
        dragTo(e.y());
        return true;
    }

    @Override
    protected void released() { dragging = false; }

    @Override
    protected boolean scrolled(double mouseX, double mouseY, double scrollY) {
        if (mouseX < gridX() || mouseX >= barX() + SCROLLBAR_W || mouseY < gridY() || mouseY >= gridY() + gridH()) return false;
        scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scrollY)));
        return true;
    }

    void auditScrollEnd() { scrollToEnd = true; scrolledToPattern = true; }

    @Override
    public void onClose() {
        st.save();
        super.onClose();
    }
}
