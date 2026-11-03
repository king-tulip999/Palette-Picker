package dev.jordan.palettepicker.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.jordan.palettepicker.core.BlockData.Block;
import dev.jordan.palettepicker.core.Floors;
import dev.jordan.palettepicker.core.Generator;
import dev.jordan.palettepicker.core.Generator.EdgeStyle;
import dev.jordan.palettepicker.core.ShareCode;
import dev.jordan.palettepicker.core.Shapes;
import dev.jordan.palettepicker.core.Themes;
import dev.jordan.palettepicker.core.Themes.Mode;
import dev.jordan.palettepicker.core.Themes.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Step 1: mode, base block (search or held item), theme, mood, size, path / floor options. The right column is a block search;
 * its results show only while there's a query. Otherwise the saved palettes (if any) sit on top, and a grid of every block,
 * grouped (wood, stone...), fills the rest.
 */
public class SetupScreen extends PickerScreen {
    /** Option rows under the theme buttons (3 rows of 16 + GAP from top + 66). */
    /** Size slider; the value is drawn after it. */
    private static final int SLIDER_W = 124;
    private static final int THEME_COLS = 3, ROW_MOOD = 128, ROW_BLOCKS = 150, ROW_WIDTH = 172, ROW_SIDES = 194;
    private static final int COL_X = 204, LIST_Y = 52, SLOT_CELL = 18, RESULT_ROW = 18, SAVED_Y = 64, SAVED_ROW_H = 20 + GAP, SAVED_TEXT_LEFT = 47;

    private enum Drag { RESULTS, SAVED, GRID }

    /** Grid rows: a group header (blocks empty) or up to a row's worth of blocks of one group. */
    private record Row(String header, List<Block> blocks) {}

    private final PickerState st;
    private String query = "";
    private int resultScroll, savedScroll, gridScroll;
    private List<Row> gridRows = List.of();
    private boolean scrolledToBase;
    private String pickedId;
    /** The saved palette whose delete button was clicked once and now asks "Delete?". */
    private PickerState.Saved confirmDelete;
    private Drag dragging;
    private String toast = "";
    private List<Block> matches = List.of();
    private final List<PickerButton> savedButtons = new ArrayList<>();
    private EditBox search;
    private long pickedAt;

    public SetupScreen(PickerState st) {
        super(Component.literal("Palette Picker"));
        this.st = st;
    }

    private int colX() { return left + COL_X; }
    // The right column takes the rest of the window; its lists get as many rows as fit.
    private int colW() { return W - COL_X; }
    private int listW() { return colW() - SCROLLBAR_W - 1; }
    private int resultRows() { return (H - LIST_Y - 16) / RESULT_ROW; }
    /** Saved rows shown: as many as there are, up to about a third of the column, so the block grid keeps most of it. */
    private int savedRows() { return Math.min(st.saved.size(), Math.max(1, (H - SAVED_Y - 16) / 3 / SAVED_ROW_H)); }
    private int listY() { return top + LIST_Y; }
    private int resultsH() { return resultRows() * RESULT_ROW; }
    private int savedY() { return top + SAVED_Y; }
    private int savedH() { return savedRows() * SAVED_ROW_H - 2; }
    private int barX() { return colX() + listW() + 1; }
    private boolean searching() { return !query.isBlank(); }
    /** The block grid's label, then its cells; right under the saved list, or at the top when nothing is saved. */
    private int gridLabelY() { return st.saved.isEmpty() ? listY() : savedY() + savedH() + 8; }
    private int gridY() { return gridLabelY() + 12; }
    private int gridCols() { return listW() / SLOT_CELL; }
    private int gridVisible() { return Math.max(1, (top + H - 14 - gridY()) / SLOT_CELL); }
    private int gridH() { return gridVisible() * SLOT_CELL; }

    @Override
    protected void layout() {
        int modeW = 0;
        for (Mode m : Mode.values()) modeW = Math.max(modeW, PickerButton.fit(Themes.of(m).name(), 48));
        int mx = left + W - (modeW + GAP) * Mode.values().length + GAP;
        for (Mode m : Mode.values()) {
            addRenderableWidget(toggle(Themes.of(m).name(), st.mode == m, mx, top, modeW, () -> { st.mode = m; st.resetPicks(); })); mx += modeW + GAP;
        }

        addRenderableWidget(PickerButton.of("Use held", left + 136, top + 33, 60, 16, b -> useHeld()));

        Theme[] themes = Theme.values();
        int themeW = (196 - GAP * (THEME_COLS - 1)) / THEME_COLS;
        for (int i = 0; i < themes.length; i++) {
            Theme t = themes[i];
            addRenderableWidget(toggle(t.label, st.theme == t, left + (i % THEME_COLS) * (themeW + GAP), top + 66 + (i / THEME_COLS) * (16 + GAP), themeW, () -> { st.theme = t; st.resetPicks(); }));
        }
        PickerButton[] mood = new PickerButton[1];
        mood[0] = addRenderableWidget(PickerButton.dropdown(st.tone.label, null, left + 40, top + ROW_MOOD, 156, 16,
                b -> openMenu(mood[0], PaletteScreen.moodMenu(st, this::rebuildWidgets))));

        boolean counts = Generator.baseCounts(st.mode, st.data.get(st.base).orElseThrow());
        int max = Generator.maxSize(st.mode, st.edge, counts);
        for (int n = 3; n <= max; n++) {
            int size = n;
            addRenderableWidget(toggle(String.valueOf(n), Math.min(st.size, max) == n, left + 40 + (n - 3) * (18 + GAP), top + ROW_BLOCKS, 18, () -> st.size = size));
        }
        if (st.mode == Mode.PATH) {
            int[] widths = {3, 5, 7};
            for (int i = 0; i < widths.length; i++) {
                int w = widths[i];
                addRenderableWidget(toggle(String.valueOf(w), st.pathWidth == w, left + 40 + i * (18 + GAP), top + ROW_WIDTH, 18, () -> st.pathWidth = w));
            }
            int blendW = PickerButton.fit("Grass blend", 66), borderW = PickerButton.fit("Border", 48);
            addRenderableWidget(toggle("Grass blend", st.edge == EdgeStyle.BLEND, left + 40, top + ROW_SIDES, blendW, () -> { st.edge = EdgeStyle.BLEND; st.locks.clear(); }));
            addRenderableWidget(toggle("Border", st.edge == EdgeStyle.SOLID, left + 40 + blendW + GAP, top + ROW_SIDES, borderW, () -> { st.edge = EdgeStyle.SOLID; st.locks.clear(); }));
        } else if (st.mode == Mode.FLOOR) {
            addRenderableWidget(new PickerSlider(left + 40, top + ROW_WIDTH, SLIDER_W, 16, Floors.MIN_SIZE, Floors.MAX_SIZE, st.floorSize, v -> st.floorSize = v, st::save));
            int sx = left + 40;
            for (Floors.Shape shape : Floors.Shape.values()) {
                int w = PickerButton.fit(shape.label, 40);
                addRenderableWidget(toggle(shape.label, st.floorShape == shape, sx, top + ROW_SIDES, w, () -> st.floorShape = shape));
                sx += w + GAP;
            }
        } else if (st.mode == Mode.SHAPE) {
            addRenderableWidget(new PickerSlider(left + 40, top + ROW_WIDTH, SLIDER_W, 16, Shapes.MIN_SIZE, Shapes.MAX_SIZE, st.shapeSize, v -> st.shapeSize = v, st::save));
            PickerButton[] kind = new PickerButton[1];
            kind[0] = addRenderableWidget(PickerButton.dropdown(st.shapeKind.label, null, left + 40, top + ROW_SIDES, 156, 16, b -> {
                List<MenuItem> items = new ArrayList<>();
                for (Shapes.Kind k : Shapes.Kind.values()) items.add(new MenuItem(k.label, null, k == st.shapeKind, () -> { st.shapeKind = k; rebuildWidgets(); }));
                openMenu(kind[0], items);
            }));
        }

        // Shapes open straight on the layers; their Back leads to the palette.
        int pasteW = PickerButton.fit("Paste code", 60);
        addRenderableWidget(PickerButton.primary("Generate palette", left, top + H - 20, 196 - pasteW - GAP, 20,
                b -> minecraft.setScreenAndShow(st.mode == Mode.SHAPE ? new ShapeScreen(st) : new PaletteScreen(st))));
        addRenderableWidget(PickerButton.of("Paste code", left + 196 - pasteW, top + H - 20, pasteW, 20, b -> pasteCode()));

        // Saved palettes: always built, hidden while the search shows results (so typing never rebuilds the screen and drops focus).
        savedButtons.clear();
        savedScroll = Math.max(0, Math.min(st.saved.size() - savedRows(), savedScroll));
        for (int i = savedScroll; i < Math.min(st.saved.size(), savedScroll + savedRows()); i++) {
            PickerState.Saved s = st.saved.get(i);
            int y = savedY() + (i - savedScroll) * SAVED_ROW_H;
            // Delete takes two clicks: x, then "Delete?" in the same place.
            boolean confirming = s == confirmDelete;
            int delW = confirming ? PickerButton.fit("Delete?", 20) : 20;
            savedButtons.add(addRenderableWidget(PickerButton.leftAligned(s.title(), SAVED_TEXT_LEFT, colX(), y, listW() - delW - GAP, 20,
                    b -> { st.restore(s); minecraft.setScreenAndShow(new PaletteScreen(st)); })));
            savedButtons.add(addRenderableWidget(PickerButton.of(confirming ? "Delete?" : "×", colX() + listW() - delW, y, delW, 20, b -> {
                if (confirming) { st.saved.remove(s); confirmDelete = null; st.save(); }
                else confirmDelete = s;
                rebuildWidgets();
            })));
        }
        if (confirmDelete != null && !st.saved.contains(confirmDelete)) confirmDelete = null;

        List<Row> rows = new ArrayList<>();
        BlockGroups.group(st.data.blocks()).forEach((group, blocks) -> {
            rows.add(new Row(group.label, List.of()));
            for (int i = 0; i < blocks.size(); i += gridCols()) rows.add(new Row(null, blocks.subList(i, Math.min(blocks.size(), i + gridCols()))));
        });
        gridRows = rows;
        if (!scrolledToBase) { // open with the current base in view
            scrolledToBase = true;
            for (int r = 0; r < gridRows.size(); r++)
                if (gridRows.get(r).blocks().stream().anyMatch(b -> b.id().equals(st.base))) { gridScroll = r - 1; break; }
        }
        gridScroll = Math.max(0, Math.min(maxGridScroll(), gridScroll));

        search = new EditBox(font, colX(), top + 32, colW(), 14, Component.literal("Search"));
        search.setHint(Component.literal("Search blocks… (Enter picks the first)"));
        search.setValue(query);
        search.setResponder(q -> { query = q; resultScroll = 0; refilter(); });
        addRenderableWidget(search);
        refilter();
        st.save();
    }

    /** An option button that shows as selected when on, and rebuilds the screen after changing state. */
    private PickerButton toggle(String label, boolean on, int x, int y, int w, Runnable action) {
        return PickerButton.option(label, on, x, y, w, 16, btn -> { action.run(); rebuildWidgets(); });
    }

    /** Matches on id or name; names that start with the query first, then shorter names. */
    private void refilter() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        String id = q.replace(' ', '_');
        matches = q.isEmpty() ? List.of() : st.data.blocks().stream()
                .filter(b -> b.id().contains(id) || b.name().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing((Block b) -> !b.name().toLowerCase(Locale.ROOT).startsWith(q)).thenComparingInt(b -> b.name().length()))
                .toList();
        for (PickerButton b : savedButtons) b.visible = !searching();
    }

    private int maxResultScroll() { return Math.max(0, matches.size() - resultRows()); }
    private int maxGridScroll() { return Math.max(0, gridRows.size() - gridVisible()); }

    private void pick(String id) {
        st.base = id;
        pickedId = id;
        st.resetPicks();
        pickedAt = System.currentTimeMillis();
        query = "";
        BlockItems.playPlace(minecraft, id);
        rebuildWidgets();
    }

    private void useHeld() {
        String id = BlockItems.heldBlockId(minecraft);
        if (id != null && st.data.get(id).isPresent()) { toast = ""; pick(id); }
        else toast = id == null ? "Hold a block first." : "That block isn't in the palette library.";
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Block base = st.data.get(st.base).orElseThrow();
        boolean counts = Generator.baseCounts(st.mode, base);
        long since = System.currentTimeMillis() - pickedAt;
        label(g, title.getString(), left, top + 4, Style.TEXT);
        g.fill(left, top + 18, left + W, top + 19, Style.SURFACE_BORDER);
        g.fill(left + 199, top + 22, left + 200, top + H, Style.SURFACE_BORDER);

        label(g, st.mode == Mode.PATH ? "Match this block" : st.mode == Mode.FLOOR ? "Main floor block" : "Base block", left, top + 22, Style.MUTED);
        BlockItems.slotFrame(g, left, top + 32, 1, false);
        region("base slot", left, top + 32, SLOT_CELL, SLOT_CELL);
        BlockItems.itemPopped(g, base.id(), left + 1, top + 33, 1, BlockItems.pop(since));
        List<String> nameLines = BlockItems.wrap(font, base.name(), 136 - GAP - 22, 2); // up to the Use held button
        int ny = nameLines.size() == 1 ? top + 37 : top + 33;
        for (String line : nameLines) { label(g, line, left + 22, ny, Style.TEXT); ny += 9; }
        label(g, "Theme", left, top + 56, Style.MUTED);
        label(g, "Mood", left, top + ROW_MOOD + 4, Style.MUTED);
        label(g, "Blocks", left, top + ROW_BLOCKS + 4, Style.MUTED);
        if (st.mode == Mode.PATH) {
            label(g, "Width", left, top + ROW_WIDTH + 4, Style.MUTED);
            label(g, "Sides", left, top + ROW_SIDES + 4, Style.MUTED);
        } else if (st.mode == Mode.FLOOR || st.mode == Mode.SHAPE) {
            label(g, "Size", left, top + ROW_WIDTH + 4, Style.MUTED);
            label(g, "Shape", left, top + ROW_SIDES + 4, Style.MUTED);
            label(g, String.valueOf(st.mode == Mode.FLOOR ? st.floorSize : st.shapeSize), left + 40 + SLIDER_W + 6, top + ROW_WIDTH + 4, Style.TEXT);
        }
        // One message area between the last option row and Generate (2 lines): a toast replaces the path/floor note while shown.
        int msgY = top + (st.mode == Mode.BUILDING ? ROW_BLOCKS : ROW_SIDES) + 16 + GAP + 2;
        if (!toast.isEmpty()) paragraph(g, Component.literal(toast), left, msgY, 196, Style.HIGHLIGHT);
        else if (!counts) {
            int n = Math.min(st.size, Generator.maxSize(st.mode, st.edge, false));
            String kind = st.mode == Mode.FLOOR ? "floor" : "path";
            paragraph(g, Component.literal("Not a " + kind + " block: you get " + n + " " + kind + " blocks matched to its colors."), left, msgY, 196, Style.HIGHLIGHT);
        }

        label(g, "Change base block", colX(), top + 22, Style.MUTED);
        if (searching()) drawResults(g, mouseX, mouseY);
        else {
            drawSaved(g, mouseX, mouseY);
            drawGrid(g, mouseX, mouseY, since);
        }
    }

    private void drawResults(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = colX(), y0 = listY();
        if (matches.isEmpty()) { label(g, "No blocks match \"" + BlockItems.ellipsizeQuietly(font, query.trim(), 150) + "\".", x, y0 + 4, Style.MUTED); return; }
        region("search results", x, y0, barX() + SCROLLBAR_W - x, resultsH());
        int shown = Math.min(resultRows(), matches.size() - resultScroll);
        for (int i = 0; i < shown; i++) {
            Block b = matches.get(resultScroll + i);
            int y = y0 + i * RESULT_ROW;
            boolean sel = b.id().equals(st.base), over = mouseX >= x && mouseX < x + listW() && mouseY >= y && mouseY < y + RESULT_ROW;
            if (over) g.fill(x, y, x + listW(), y + RESULT_ROW, Style.HOVER);
            else if (resultScroll + i == 0) g.fill(x, y, x + listW(), y + RESULT_ROW, 0x18FFFFFF); // what Enter picks
            BlockItems.item(g, b.id(), x + 1, y + 1, 1);
            label(g, BlockItems.ellipsizeQuietly(font, b.name(), listW() - 24), x + 21, y + 5, sel ? Style.GOOD : Style.TEXT);
        }
        scrollbar(g, barX(), y0, resultsH(), resultScroll, resultRows(), matches.size(), dragging == Drag.RESULTS || overBar(mouseX, mouseY, y0, resultsH()));
        label(g, matches.size() + (matches.size() == 1 ? " block" : " blocks") + (matches.size() > resultRows() ? " · scroll for more" : ""),
                x, y0 + resultsH() + 4, Style.MUTED);
    }

    private void drawSaved(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = colX();
        if (st.saved.isEmpty()) return; // the block grid takes the whole column
        label(g, "Saved palettes", x, listY(), Style.MUTED);
        for (int i = savedScroll; i < Math.min(st.saved.size(), savedScroll + savedRows()); i++) {
            List<String> blocks = st.saved.get(i).blocks();
            for (int k = 0; k < Math.min(blocks.size(), 3); k++) BlockItems.item(g, blocks.get(k), x + 3 + k * 14, savedY() + 2 + (i - savedScroll) * SAVED_ROW_H, 1);
        }
        scrollbar(g, barX(), savedY(), savedH(), savedScroll, savedRows(), st.saved.size(), dragging == Drag.SAVED || overBar(mouseX, mouseY, savedY(), savedH()));
        region("saved list", x, savedY(), barX() + SCROLLBAR_W - x, savedH());
    }

    private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY, long since) {
        int x0 = colX(), y0 = gridY(), cols = gridCols();
        label(g, "All blocks", x0, gridLabelY(), Style.MUTED);
        region("block grid", x0, y0, barX() + SCROLLBAR_W - x0, gridH());
        Block hovered = null;
        int ringX = Integer.MIN_VALUE, ringY = 0;
        for (int r = gridScroll; r < Math.min(gridRows.size(), gridScroll + gridVisible()); r++) {
            Row row = gridRows.get(r);
            int y = y0 + (r - gridScroll) * SLOT_CELL;
            if (row.header() != null) {
                label(g, row.header(), x0 + 1, y + 6, Style.MUTED);
                g.fill(x0 + font.width(row.header()) + 6, y + 10, x0 + cols * SLOT_CELL, y + 11, Style.SURFACE_BORDER);
                continue;
            }
            for (int c = 0; c < row.blocks().size(); c++) {
                int x = x0 + c * SLOT_CELL;
                Block b = row.blocks().get(c);
                boolean sel = b.id().equals(st.base);
                BlockItems.slot(g, b.id(), x, y, 1, sel);
                if (sel) { ringX = x; ringY = y; }
                if (mouseX >= x && mouseX < x + SLOT_CELL && mouseY >= y && mouseY < y + SLOT_CELL) { hovered = b; BlockItems.hover(g, x, y, SLOT_CELL); }
                if (b.id().equals(pickedId) && since < 300) { // white flash that fades out on the cell you just picked
                    int a = (int) (0xC0 * (1 - since / 300f));
                    g.fill(x + 1, y + 1, x + SLOT_CELL - 1, y + SLOT_CELL - 1, a << 24 | 0xFFFFFF);
                }
            }
        }
        if (ringX != Integer.MIN_VALUE) BlockItems.ring(g, ringX, ringY, SLOT_CELL); // after all cells, so the next row can't cover it
        scrollbar(g, barX(), y0, gridH(), gridScroll, gridVisible(), gridRows.size(), dragging == Drag.GRID || overBar(mouseX, mouseY, y0, gridH()));
        String footer = hovered != null ? hovered.name() : st.data.blocks().size() + " blocks" + (maxGridScroll() > 0 ? " · scroll for more" : "");
        label(g, BlockItems.ellipsizeQuietly(font, footer, listW()), x0, y0 + gridH() + 4, Style.MUTED);
    }

    /** The block at a point in the grid, or null. */
    private Block gridBlock(double mx, double my) {
        if (mx < colX() || my < gridY()) return null;
        int col = (int) ((mx - colX()) / SLOT_CELL), row = (int) ((my - gridY()) / SLOT_CELL);
        if (col >= gridCols() || row >= gridVisible() || gridScroll + row >= gridRows.size()) return null;
        List<Block> blocks = gridRows.get(gridScroll + row).blocks();
        return col < blocks.size() ? blocks.get(col) : null;
    }

    private boolean overBar(double x, double y, int barY, int barH) { return x >= barX() && x < barX() + SCROLLBAR_W && y >= barY && y < barY + barH; }

    private void dragTo(double mouseY) {
        if (dragging == Drag.RESULTS) resultScroll = scrollAt(mouseY, listY(), resultsH(), resultRows(), matches.size());
        else if (dragging == Drag.GRID) gridScroll = scrollAt(mouseY, gridY(), gridH(), gridVisible(), gridRows.size());
        else if (dragging == Drag.SAVED) {
            int s = scrollAt(mouseY, savedY(), savedH(), savedRows(), st.saved.size());
            if (s != savedScroll) { savedScroll = s; rebuildWidgets(); }
        }
    }

    @Override
    protected boolean clicked(MouseButtonEvent e) {
        if (e.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        if (!searching()) {
            if (!st.saved.isEmpty() && st.saved.size() > savedRows() && overBar(e.x(), e.y(), savedY(), savedH())) dragging = Drag.SAVED;
            else if (maxGridScroll() > 0 && overBar(e.x(), e.y(), gridY(), gridH())) dragging = Drag.GRID;
            else {
                Block b = gridBlock(e.x(), e.y());
                if (b == null) return false;
                pick(b.id());
                return true;
            }
            dragTo(e.y());
            return true;
        }
        if (matches.size() > resultRows() && overBar(e.x(), e.y(), listY(), resultsH())) {
            dragging = Drag.RESULTS;
            dragTo(e.y());
            return true;
        }
        if (e.x() < colX() || e.x() >= colX() + listW() || e.y() < listY()) return false;
        int row = (int) ((e.y() - listY()) / RESULT_ROW);
        if (row >= resultRows() || resultScroll + row >= matches.size()) return false;
        pick(matches.get(resultScroll + row).id());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (searching() && !matches.isEmpty() && (e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER)) {
            pick(matches.get(0).id());
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    protected boolean dragged(MouseButtonEvent e) {
        if (dragging == null) return false;
        dragTo(e.y());
        return true;
    }

    @Override
    protected void released() { dragging = null; }

    @Override
    protected boolean scrolled(double mouseX, double mouseY, double scrollY) {
        int step = -(int) Math.signum(scrollY);
        if (mouseX < colX() || mouseX >= barX() + SCROLLBAR_W) return false;
        if (searching()) {
            if (mouseY < listY() || mouseY >= listY() + resultsH()) return false;
            resultScroll = Math.max(0, Math.min(maxResultScroll(), resultScroll + step));
            return true;
        }
        if (mouseY >= gridY() && mouseY < gridY() + gridH()) {
            gridScroll = Math.max(0, Math.min(maxGridScroll(), gridScroll + step));
            return true;
        }
        if (st.saved.isEmpty() || mouseY < savedY() || mouseY >= savedY() + savedH()) return false;
        int s = Math.max(0, Math.min(st.saved.size() - savedRows(), savedScroll + step));
        if (s != savedScroll) { savedScroll = s; rebuildWidgets(); }
        return true;
    }

    /** A code on the clipboard, else the last one seen in chat (chat text can't be copied in game). */
    private void pasteCode() {
        String clip = minecraft.keyboardHandler.getClipboard();
        String code = clip != null && clip.strip().startsWith(ShareCode.PREFIX) ? clip : PickerState.lastChatCode;
        if (code == null) { toast = "Copy a palette code first (or have someone post one in chat), then press Paste code."; return; }
        PickerState.Applied a = st.applyCode(code);
        if (!a.loaded()) { toast = a.message(); return; }
        PaletteScreen next = new PaletteScreen(st);
        minecraft.setScreenAndShow(next);
        if (a.message() != null) next.flash(a.message());
    }

    void auditToast(String msg) { toast = msg; }
    void auditSearch(String q) { query = q; }
    void auditConfirmDelete() { confirmDelete = st.saved.getFirst(); }

    @Override
    public void onClose() {
        st.save();
        super.onClose();
    }
}
