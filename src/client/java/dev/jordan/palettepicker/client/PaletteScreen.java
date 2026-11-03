package dev.jordan.palettepicker.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.jordan.palettepicker.core.BlockData;
import dev.jordan.palettepicker.core.BlockData.Block;
import dev.jordan.palettepicker.core.Decor;
import dev.jordan.palettepicker.core.Floors;
import dev.jordan.palettepicker.core.Generator;
import dev.jordan.palettepicker.core.Generator.EdgeStyle;
import dev.jordan.palettepicker.core.Generator.Palette;
import dev.jordan.palettepicker.core.Generator.Score;
import dev.jordan.palettepicker.core.Generator.Slot;
import dev.jordan.palettepicker.core.Shapes;
import dev.jordan.palettepicker.core.Themes;
import dev.jordan.palettepicker.core.Themes.Mode;
import dev.jordan.palettepicker.core.Themes.Theme;
import dev.jordan.palettepicker.core.Themes.Tone;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Step 2: the palette, why each block was picked, house / path / floor preview, decor ideas, and reroll / save / give / checklist.
 * The header's dropdowns change mode, base, theme, mood and size in place, and each slot offers its other top picks.
 */
public class PaletteScreen extends PickerScreen {
    // The info panel keeps its width at the window's right edge; the middle (slots, preview, ideas) takes the rest.
    // 7 cells (a reference base + 6 slots) must end before the panel: 6 * SLOT_GAP + 34 <= MIN_W - PANEL_W - GAP.
    private static final int SLOT = 36, SLOT_GAP = 41, PANEL_W = MIN_W - 286, MIN_CELL = 8, PREVIEW_COLS = 34, ALT = 18,
            MIX_H = 14, IDEAS_MIN_W = 160;

    private final PickerState st;
    private Palette palette;
    private int selected; // 0 = base, 1.. = slots
    private boolean checklistOpen, decorOpen;
    private List<Decor.Idea> decor = List.of();
    private final Set<String> checked = new HashSet<>();
    private String toast = "";
    private long toastUntil;
    /** Left edge of the bottom-right button row; toasts go to its left. */
    private int buttonsX;
    /** Left edge of the path width / sides dropdowns; the path heading stops before it. */
    private int pathControlsX;
    /** When each cell (0 = base, 1.. = slots) last popped; see {@link BlockItems#pop}. */
    private final long[] poppedAt = new long[Themes.MAX_SIZE + 2];
    private final Map<String, BlockItems.Face> faces = new HashMap<>();
    private final Map<String, List<BlockItems.Face>> sides = new HashMap<>();
    private final Map<String, Optional<BlockItems.Face>> doorFaces = new HashMap<>();
    private final ShapeView shapeView = new ShapeView();
    /** Name of the swap option under the mouse, shown in the panel. */
    private String hoveredAlt;
    /** Path mix divider being dragged (between segment i and i + 1), or -1. */
    private int dragBoundary = -1;

    public PaletteScreen(PickerState st) {
        super(Component.literal("Palette"));
        this.st = st;
        this.palette = st.generate();
        this.decor = Decor.suggest(palette, st.data);
    }

    @Override
    protected void layout() {
        boolean creative = minecraft.player != null && minecraft.player.hasInfiniteMaterials();

        addRenderableWidget(PickerButton.of("< Back", left, top, 40, 16, b -> minecraft.setScreenAndShow(new SetupScreen(st))));
        header();

        int bx = left + W;
        bx -= 90;  // right to left, GAP apart
        if (creative) addRenderableWidget(PickerButton.primary("Give to hotbar", bx, top + H - 20, 90, 20, b -> flash(BlockItems.giveCreative(minecraft, palette.placeable()))));
        else addRenderableWidget(PickerButton.primary(checklistOpen ? "Close checklist" : "Checklist", bx, top + H - 20, 90, 20, b -> { checklistOpen = !checklistOpen; decorOpen = false; rebuildWidgets(); }));
        bx -= 50 + GAP;
        addRenderableWidget(PickerButton.of("Save", bx, top + H - 20, 50, 20, b -> save()));
        bx -= 50 + GAP;
        addRenderableWidget(PickerButton.of("Share", bx, top + H - 20, 50, 20, b -> share()));
        bx -= 60 + GAP;
        addRenderableWidget(PickerButton.of("Reroll", bx, top + H - 20, 60, 20, b -> reroll()));
        int decorW = PickerButton.fit("Hide decor", 50);
        bx -= decorW + GAP;
        addRenderableWidget(PickerButton.of(decorOpen ? "Hide decor" : "Decor", bx, top + H - 20, decorW, 20, b -> { decorOpen = !decorOpen; checklistOpen = false; rebuildWidgets(); }));
        if (st.mode == Mode.FLOOR) {
            bx -= 60 + GAP;
            addRenderableWidget(PickerButton.of("Patterns", bx, top + H - 20, 60, 20, b -> minecraft.setScreenAndShow(new FloorScreen(st))));
        }
        if (st.mode == Mode.SHAPE) {
            bx -= 60 + GAP;
            addRenderableWidget(PickerButton.of("Layers", bx, top + H - 20, 60, 20, b -> minecraft.setScreenAndShow(new ShapeScreen(st))));
        }
        buttonsX = bx;

        if (st.mode == Mode.PATH) pathControls();
        if (st.mode == Mode.PATH && !st.pathMix.isEmpty())
            addRenderableWidget(PickerButton.of("Reset", left + mixW() - 36, mixY() - 2, 36, 12, b -> { st.pathMix.clear(); st.save(); rebuildWidgets(); }));

        Slot slot = selectedSlot();
        if (slot != null && !checklistOpen && !decorOpen) {
            addRenderableWidget(PickerButton.of(slot.locked() ? "Unlock" : "Lock this block", left + panelX() + 4, top + H - 46, PANEL_W - 8, 16, b -> toggleLock(slot)));
        }
    }

    /** Dropdowns along the top: mode, base, theme, mood, size. The base takes whatever width is left. */
    private void header() {
        boolean counts = palette.baseCounts();
        int max = Generator.maxSize(st.mode, st.edge, counts), size = Math.min(st.size, max);
        String mode = Themes.of(st.mode).name(), theme = st.theme.label, mood = st.tone == Tone.ANY ? "Any mood" : "Mood: " + st.tone.label,
                blocks = size + " blocks";
        int modeW = PickerButton.fitDropdown(mode, false), themeW = PickerButton.fitDropdown("Industrial", false),
                moodW = PickerButton.fitDropdown("Mood: Muted", false), sizeW = PickerButton.fitDropdown(blocks, false);
        int x = left + 40 + GAP, end = left + W;
        int baseW = Math.min(PickerButton.fitDropdown(palette.base().name(), true), end - x - modeW - themeW - moodW - sizeW - 4 * GAP);

        PickerButton[] modeB = new PickerButton[1];
        modeB[0] = addRenderableWidget(PickerButton.dropdown(mode, null, x, top, modeW, 16, b -> {
            List<MenuItem> items = new ArrayList<>();
            for (Mode m : Mode.values()) items.add(new MenuItem(Themes.of(m).name(), null, m == st.mode, () -> { st.mode = m; st.resetPicks(); changed(); }));
            openMenu(modeB[0], items);
        }));
        x += modeW + GAP;
        PickerButton[] baseB = new PickerButton[1];
        baseB[0] = addRenderableWidget(PickerButton.dropdown(palette.base().name(), palette.base().id(), x, top, baseW, 16, b -> openMenu(baseB[0], baseMenu())));
        x += baseW + GAP;
        PickerButton[] themeB = new PickerButton[1];
        themeB[0] = addRenderableWidget(PickerButton.dropdown(theme, null, x, top, themeW, 16, b -> {
            List<MenuItem> items = new ArrayList<>();
            for (Theme t : Theme.values()) items.add(new MenuItem(t.label, null, t == st.theme, () -> { st.theme = t; st.resetPicks(); changed(); }));
            openMenu(themeB[0], items);
        }));
        x += themeW + GAP;
        PickerButton[] moodB = new PickerButton[1];
        moodB[0] = addRenderableWidget(PickerButton.dropdown(mood, null, x, top, moodW, 16, b -> openMenu(moodB[0], moodMenu(st, this::changed))));
        x += moodW + GAP;
        PickerButton[] sizeB = new PickerButton[1];
        sizeB[0] = addRenderableWidget(PickerButton.dropdown(blocks, null, x, top, sizeW, 16, b -> {
            List<MenuItem> items = new ArrayList<>();
            for (int n = 3; n <= max; n++) { int k = n; items.add(new MenuItem(n + " blocks", null, n == size, () -> { st.size = k; changed(); })); }
            openMenu(sizeB[0], items);
        }));
    }

    /** Path width and sides, right-aligned above the path preview, so they change without going back. */
    private void pathControls() {
        String width = "Width " + st.pathWidth, sides = st.edge == EdgeStyle.SOLID ? "Border" : "Grass blend";
        int sidesW = PickerButton.fitDropdown("Grass blend", false), widthW = PickerButton.fitDropdown("Width 7", false);
        int x = left + mixW() - sidesW, y = top + 76;
        PickerButton[] sidesB = new PickerButton[1];
        sidesB[0] = addRenderableWidget(PickerButton.dropdown(sides, null, x, y, sidesW, 12, b -> openMenu(sidesB[0], List.of(
                new MenuItem("Grass blend", null, st.edge == EdgeStyle.BLEND, () -> setEdge(EdgeStyle.BLEND)),
                new MenuItem("Border", null, st.edge == EdgeStyle.SOLID, () -> setEdge(EdgeStyle.SOLID))))));
        x -= widthW + GAP;
        PickerButton[] widthB = new PickerButton[1];
        widthB[0] = addRenderableWidget(PickerButton.dropdown(width, null, x, y, widthW, 12, b -> {
            List<MenuItem> items = new ArrayList<>();
            for (int w : new int[]{3, 5, 7}) items.add(new MenuItem("Width " + w, null, w == st.pathWidth, () -> { st.pathWidth = w; regenerate(); }));
            openMenu(widthB[0], items);
        }));
        pathControlsX = x;
    }

    /** Border adds a slot (and Grass blend drops it), so locks go like on the setup screen. */
    private void setEdge(EdgeStyle e) {
        if (e == st.edge) return;
        st.edge = e;
        st.locks.clear();
        changed();
    }

    /** Mood choices, shared with the setup screen. Picking one clears rerolls and locks, then runs {@code after}. */
    static List<MenuItem> moodMenu(PickerState st, Runnable after) {
        List<MenuItem> items = new ArrayList<>();
        for (Tone t : Tone.values()) items.add(new MenuItem(t.label + moodHint(t), null, t == st.tone, () -> { st.tone = t; st.resetPicks(); after.run(); }));
        return items;
    }

    private static String moodHint(Tone t) {
        return switch (t) {
            case ANY -> ""; case PALE -> "  · light, washed out"; case SOFT -> "  · light, gentle"; case MUTED -> "  · greyed, low color";
            case DEEP -> "  · rich, saturated"; case DARK -> "  · dark, moody";
        };
    }

    /** Base swaps without going back: any block in this palette, the held block, or the full grid. */
    private List<MenuItem> baseMenu() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("Browse all blocks…", null, false, () -> minecraft.setScreenAndShow(new SetupScreen(st))));
        String held = BlockItems.heldBlockId(minecraft);
        if (held != null && !held.equals(st.base) && st.data.get(held).isPresent())
            items.add(new MenuItem("Held: " + st.data.get(held).get().name(), held, false, () -> setBase(held)));
        items.add(MenuItem.heading("Build around one of these:"));
        for (Slot sl : palette.slots()) { String id = sl.block().id(); items.add(new MenuItem(sl.block().name(), id, false, () -> setBase(id))); }
        return items;
    }

    private void setBase(String id) {
        st.base = id;
        st.resetPicks();
        BlockItems.playPlace(minecraft, id);
        changed();
    }

    /** After a header change: new palette, and every cell pops. */
    private void changed() {
        regenerate();
        for (int i = 0; i <= palette.slots().size(); i++) popCell(i);
    }

    private Slot selectedSlot() { return selected == 0 ? null : palette.slots().get(Math.min(selected, palette.slots().size()) - 1); }

    private void regenerate() {
        palette = st.generate();
        selected = Math.min(selected, palette.slots().size());
        decor = Decor.suggest(palette, st.data);
        st.save();
        rebuildWidgets();
    }

    /** Puts {@code b} (one of the slot's top picks) in the slot: a locked slot is re-locked to it, otherwise the reroll seed that picks it is found. */
    private void swapTo(Slot s, Block b) {
        if (b.id().equals(s.block().id())) return;
        if (s.locked()) st.locks.put(s.role().id(), b.id());
        else {
            int index = palette.slots().indexOf(s), rank = s.options().indexOf(b), cur = st.seeds.getOrDefault(s.role().id(), 0);
            for (int seed = cur + 1; seed < cur + 1000; seed++)
                if (Generator.pickIndex(seed, index, s.options().size()) == rank) { st.seeds.put(s.role().id(), seed); break; }
            if (rank == 0) st.seeds.remove(s.role().id());
        }
        List<String> before = palette.slots().stream().map(x -> x.block().id()).toList();
        BlockItems.playPlace(minecraft, b.id());
        regenerate();
        for (int i = 0; i < palette.slots().size(); i++)
            if (i >= before.size() || !before.get(i).equals(palette.slots().get(i).block().id())) popCell(i + 1);
    }

    private void reroll() {
        for (Themes.Role r : Themes.of(st.mode).roles()) if (!st.locks.containsKey(r.id())) st.seeds.merge(r.id(), 1, Integer::sum);
        List<String> before = palette.slots().stream().map(s -> s.block().id()).toList();
        regenerate();
        for (int i = 0; i < palette.slots().size(); i++) // pop the blocks that changed
            if (i >= before.size() || !before.get(i).equals(palette.slots().get(i).block().id())) popCell(i + 1);
    }

    private void popCell(int i) { if (i < poppedAt.length) poppedAt[i] = System.currentTimeMillis(); }

    private void toggleLock(Slot s) {
        if (s.locked()) st.locks.remove(s.role().id());
        else st.locks.put(s.role().id(), s.block().id());
        regenerate();
        popCell(selected);
    }

    private void save() {
        st.saved.add(0, snapshot(st, palette));
        if (st.saved.size() > 20) st.saved.remove(st.saved.size() - 1);
        st.save();
        flash("Saved. Find it under Saved on the first screen.");
    }

    /** {@code palette} as a saved entry; locks hold every block so it reproduces exactly. */
    static PickerState.Saved snapshot(PickerState st, Palette palette) {
        Map<String, String> locks = new HashMap<>();
        palette.slots().forEach(s -> locks.put(s.role().id(), s.block().id()));
        List<String> ids = palette.placeable().stream().map(Block::id).toList();
        String title = Generator.moodName(palette) + " " + Themes.of(st.mode).name().toLowerCase(Locale.ROOT) + ": "
                + String.join(", ", palette.placeable().stream().map(Block::name).toList());
        return new PickerState.Saved(title, st.mode, st.base, st.theme, st.size, st.pathWidth, st.edge, locks, ids,
                st.tone, st.floorPattern, st.floorShape, st.floorSize, st.floorBorder, st.floorShift, st.floorSeed, Map.copyOf(st.pathMix),
                st.shapeKind, st.shapeSize, st.shapeHeight, st.shapeHollow, st.shapeThick, st.shapeFill, st.shapeShift, st.shapeSeed);
    }

    private void share() {
        minecraft.keyboardHandler.setClipboard(st.shareCode(palette));
        flash("Code copied. Paste it in chat or send it; Paste code on the first screen loads it.");
    }

    void flash(String msg) { toast = msg; toastUntil = System.currentTimeMillis() + 3500; }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Themes.ModeDef md = Themes.of(st.mode);
        String mood = Generator.moodName(palette);
        g.fill(left, top + 18, left + W, top + 19, Style.SURFACE_BORDER);

        // Slots: base first, then each role.
        List<String[]> cells = new ArrayList<>();
        cells.add(new String[]{palette.base().id(), palette.baseCounts() ? md.baseLabel() : "Ref"});
        palette.slots().forEach(s -> cells.add(new String[]{s.block().id(), s.role().label()}));
        long now = System.currentTimeMillis();
        for (int i = 0; i < cells.size(); i++) {
            int x = left + i * SLOT_GAP, y = top + 24;
            BlockItems.slotFrame(g, x, y, 2, i == selected);
            region("slot " + cells.get(i)[1], x, y, SLOT - 2, SLOT - 2);
            if (i == selected) BlockItems.ring(g, x, y, SLOT - 2);
            BlockItems.itemPopped(g, cells.get(i)[0], x + 1, y + 1, 2, BlockItems.pop(now - poppedAt[i]));
            if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT + 12) BlockItems.hover(g, x, y, SLOT - 2);
            if (i > 0 && palette.slots().get(i - 1).locked()) g.text(font, "L", x + SLOT - 7, y + 3, Style.HIGHLIGHT, true);
            String label = cells.get(i)[1];
            label(g, label, x + (SLOT - font.width(label)) / 2, y + SLOT + 3, i == selected ? Style.GOOD : Style.MUTED);
        }

        int y = top + 78;
        int textX = left, textW = panelX() - 10;
        if (st.mode == Mode.BUILDING) {
            String[][] house = Generator.buildingPreview(palette);
            int hw = Generator.HOUSE_W * houseCell();
            label(g, mood, left, y, Style.HIGHLIGHT);
            region("house preview", left, y + 11, hw, Generator.HOUSE_H * houseCell());
            for (int r = 0; r < house.length; r++) {
                for (int c = 0; c < house[r].length; c++) {
                    String id = house[r][c];
                    int x = left + c * houseCell(), cy = y + 11 + r * houseCell();
                    if (id == null) continue;
                    if (id.equals(Generator.WINDOW)) {
                        g.fill(x, cy, x + houseCell(), cy + houseCell(), 0xFF1C2833);
                        BlockItems.face(g, sides.computeIfAbsent("glass", k -> BlockItems.sideFace(minecraft, k)), x, cy, houseCell());
                    } else if (id.equals(Generator.DOOR)) {
                        // The suggested door's own texture (top row, then bottom); doors with windows show the dark interior through them.
                        String door = decor.stream().map(Decor.Idea::id).filter(d -> d.endsWith("_door")).findFirst().orElse("oak_door") + (r % 2 == 0 ? "_top" : "_bottom");
                        Optional<BlockItems.Face> tex = doorFaces.computeIfAbsent(door, k -> BlockItems.texture(minecraft, k));
                        g.fill(x, cy, x + houseCell(), cy + houseCell(), tex.isPresent() ? 0xFF1C2833 : 0xFF4A3220);
                        if (tex.isPresent()) { BlockItems.face(g, tex.get(), x, cy, houseCell()); continue; }
                        g.fill(x + 1, cy + (r % 2 == 0 ? 1 : 0), x + houseCell() - 1, cy + houseCell() - (r % 2 == 0 ? 0 : 1), 0xFF6B4A2E);
                        if (r % 2 == 1) g.fill(x + houseCell() - 3, cy + 1, x + houseCell() - 2, cy + 3, 0xFFC8B070); // handle
                    } else BlockItems.face(g, sides.computeIfAbsent(id, k -> BlockItems.sideFace(minecraft, k)), x, cy, houseCell());
                }
            }
            textX = left + Math.max(hw, font.width(mood)) + 8;
            textW = left + panelX() - 10 - textX;
        }
        if (st.mode == Mode.PATH) {
            int room = pathControlsX - GAP - left;
            label(g, BlockItems.ellipsizeQuietly(font, mood, room), left, y, Style.HIGHLIGHT);
            if (font.width(mood + " · path, top down") <= room) label(g, " · path, top down", left + font.width(mood), y, Style.MUTED);
            String[][] grid = Generator.pathPreview(palette, st.pathWidth, PREVIEW_COLS, st.pathMix);
            region("path preview", left, y + 11, PREVIEW_COLS * pathCell(), grid.length * pathCell());
            for (int r = 0; r < grid.length; r++)
                for (int c = 0; c < grid[r].length; c++)
                    BlockItems.face(g, faces.computeIfAbsent(grid[r][c], id -> BlockItems.topFace(minecraft, id)), left + c * pathCell(), y + 11 + r * pathCell(), pathCell());
            y += 14 + grid.length * pathCell();
            drawMix(g, mouseX, mouseY, y);
            y = barY() + MIX_H + 4;
        }
        List<String[]> ideas = new ArrayList<>(Generator.ideas(palette, st.pathMix));
        if (st.mode == Mode.PATH) ideas.removeIf(i -> i[0].equals("Fill:")); // the mix bar shows it
        if (st.mode == Mode.SHAPE) {
            String[][][] shape = st.shape(palette);
            String head = mood + " · " + st.shapeSize + " wide " + st.shapeKind.label.toLowerCase(Locale.ROOT);
            label(g, mood, left, y, Style.HIGHLIGHT);
            label(g, head.substring(mood.length()), left + font.width(mood), y, Style.MUTED);
            region("shape preview", left, y + 11, previewSize(), previewSize());
            Style.box(g, left, y + 11, previewSize(), previewSize(), Style.SLOT, Style.SLOT_BORDER);
            shapeView.draw(g, st, shape, shape.length - 1, left + 2, y + 13, previewSize() - 4, previewSize() - 4);
            if (overPreview(mouseX, mouseY)) BlockItems.hover(g, left, y + 11, previewSize());
            textX = left + Math.max(previewSize(), font.width(head)) + 8;
            textW = left + panelX() - 10 - textX;
            int total = Shapes.counts(shape).values().stream().mapToInt(Integer::intValue).sum();
            ideas.add(new String[]{"Needs:", Shapes.amount(total) + " blocks. Click the view to build it layer by layer."});
        }
        if (st.mode == Mode.FLOOR) {
            String[][] floor = st.floor(palette);
            int cell = floorCell(st.floorSize), size = st.floorSize * cell;
            String head = mood + " · " + st.pattern().name();
            label(g, mood, left, y, Style.HIGHLIGHT);
            label(g, " · " + st.pattern().name(), left + font.width(mood), y, Style.MUTED);
            region("floor preview", left, y + 11, size, size);
            for (int r = 0; r < floor.length; r++)
                for (int c = 0; c < floor[r].length; c++)
                    if (floor[r][c] == null) continue;
                    else if (cell >= 4) BlockItems.face(g, faces.computeIfAbsent(floor[r][c], id -> BlockItems.topFace(minecraft, id)), left + c * cell, y + 11 + r * cell, cell);
                    else g.fill(left + c * cell, y + 11 + r * cell, left + (c + 1) * cell, y + 11 + (r + 1) * cell, 0xFF000000 | st.data.get(floor[r][c]).map(b -> b.color(BlockData.Face.TOP)).orElse(0x808080));
            if (overPreview(mouseX, mouseY)) BlockItems.hover(g, left, y + 11, size);
            textX = left + Math.max(size, font.width(head)) + 8;
            textW = left + panelX() - 10 - textX;
            List<String> need = new ArrayList<>();
            Floors.counts(floor).forEach((id, n) -> need.add(st.data.get(id).map(Block::name).orElse(id) + " x" + n));
            ideas.add(new String[]{"Needs:", String.join(", ", need) + "."});
        }
        for (String[] idea : ideas) {
            Component line = Component.literal(idea[0]).withColor(Style.HIGHLIGHT & 0xFFFFFF).append(Component.literal(" " + idea[1]).withColor(Style.TEXT & 0xFFFFFF));
            int h = font.split(line, textW).size() * font.lineHeight;
            if (y + 2 + h > top + H - 20 - GAP) continue; // long block names: drop an idea rather than run into the buttons
            paragraph(g, line, textX, y + 2, textW, Style.TEXT);
            y += h + 4;
        }

        drawPanel(g, mouseX, mouseY);
        if (System.currentTimeMillis() < toastUntil) toast(g, toast, left, top + H, buttonsX - GAP - left); // bottom row, left of its buttons
    }

    // ---- Path mix: a bar of the fill blocks sized by share; drag the dividers to trade share between neighbours ----

    private int mixY() { return top + 78 + 14 + (st.pathWidth + 4) * pathCell(); }
    private int barY() { return mixY() + 12; }

    /** Fill roles and their shares, main first, summing to 1. */
    private List<Map.Entry<String, Double>> mixShares() {
        Map<String, Double> m = new HashMap<>();
        Generator.pathShares(palette, false, st.pathMix).forEach(e -> m.put(e.getKey(), e.getValue()));
        return Generator.defaultPathMix(palette).keySet().stream().map(r -> Map.entry(r, m.get(r))).toList();
    }

    private void drawMix(GuiGraphicsExtractor g, int mouseX, int mouseY, int y) {
        List<Map.Entry<String, Double>> shares = mixShares();
        int by = barY(), near = boundaryAt(mouseX, mouseY);
        String hover = null;
        region("path mix", left, by - 1, mixW(), MIX_H + 2);
        double acc = 0;
        for (int i = 0; i < shares.size(); i++) {
            Block b = palette.block(shares.get(i).getKey());
            double share = shares.get(i).getValue();
            int x0 = left + (int) Math.round(acc * mixW());
            acc += share;
            int x1 = left + (int) Math.round(acc * mixW());
            if (x1 <= x0) continue;
            g.enableScissor(x0, by, x1, by + MIX_H);
            for (int tx = x0; tx < x1; tx += MIX_H) BlockItems.face(g, faces.computeIfAbsent(b.id(), id -> BlockItems.topFace(minecraft, id)), tx, by, MIX_H);
            g.disableScissor();
            String pct = Math.round(share * 100) + "%";
            if (x1 - x0 >= font.width(pct) + 6) {
                int tx = (x0 + x1 - font.width(pct)) / 2;
                g.fill(tx - 2, by + 2, tx + font.width(pct) + 1, by + MIX_H - 2, 0xA0000000);
                label(g, pct, tx, by + 3, 0xFFFFFFFF);
            }
            if (mouseX >= x0 && mouseX < x1 && mouseY >= by && mouseY < by + MIX_H) hover = b.name() + ": " + pct + " of the path";
        }
        acc = 0;
        for (int i = 0; i < shares.size() - 1; i++) { // dividers on top of the segments
            acc += shares.get(i).getValue();
            int x = left + (int) Math.round(acc * mixW());
            boolean hot = i == dragBoundary || (dragBoundary < 0 && i == near);
            g.fill(x - 1, by - 1, x + 1, by + MIX_H + 1, hot ? Style.HIGHLIGHT : 0xFFFFFFFF);
        }
        String head = hover != null ? hover : st.pathMix.isEmpty() ? "Mix · drag the white lines to change amounts" : "Mix · your amounts";
        label(g, BlockItems.ellipsizeQuietly(font, head, mixW() - 40 - GAP), left, y, hover != null ? Style.TEXT : Style.MUTED);
    }

    /** The divider within 3px of the mouse, or -1. */
    private int boundaryAt(double mx, double my) {
        if (st.mode != Mode.PATH || my < barY() - 2 || my >= barY() + MIX_H + 2) return -1;
        List<Map.Entry<String, Double>> shares = mixShares();
        int best = -1;
        double acc = 0, bestD = 3.5;
        for (int i = 0; i < shares.size() - 1; i++) {
            acc += shares.get(i).getValue();
            double d = Math.abs(mx - (left + acc * mixW()));
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    @Override
    protected boolean dragged(MouseButtonEvent e) {
        if (dragBoundary < 0) return false;
        List<Map.Entry<String, Double>> shares = mixShares();
        double lo = 0;
        for (int i = 0; i < dragBoundary; i++) lo += shares.get(i).getValue();
        double hi = lo + shares.get(dragBoundary).getValue() + shares.get(dragBoundary + 1).getValue();
        double at = Math.round((e.x() - left) / mixW() * 100) / 100.0; // whole percents
        at = Math.max(lo + 0.01, Math.min(hi - 0.01, at));
        st.pathMix.clear();
        for (int i = 0; i < shares.size(); i++) {
            double v = i == dragBoundary ? at - lo : i == dragBoundary + 1 ? hi - at : shares.get(i).getValue();
            st.pathMix.put(shares.get(i).getKey(), v);
        }
        return true;
    }

    @Override
    protected void released() {
        if (dragBoundary < 0) return;
        dragBoundary = -1;
        st.save();
        rebuildWidgets(); // shows Reset
    }

    private int panelX() { return W - PANEL_W; }

    // Previews grow into the room between the slots, the panel and the buttons (at least 8px a block).
    /** House front: leaves IDEAS_MIN_W beside it for the ideas. */
    private int houseCell() {
        return Math.max(MIN_CELL, Math.min((panelX() - 10 - IDEAS_MIN_W) / Generator.HOUSE_W, (H - 78 - 11 - 24 - GAP) / Generator.HOUSE_H));
    }

    /** Path: as wide as the middle, leaving height for the mix bar and a few lines of ideas under it. */
    private int pathCell() { return Math.max(MIN_CELL, Math.min((panelX() - 10) / PREVIEW_COLS, (H - 78 - 14 - 12 - MIX_H - 70) / (st.pathWidth + 4))); }

    private int mixW() { return PREVIEW_COLS * pathCell(); }

    /** Floor or shape preview size: what fits under the slots and above the buttons, leaving room for the ideas beside it. */
    private int previewSize() { return Math.max(140, Math.min(H - 78 - 11 - 24 - GAP, panelX() - 10 - 8 - 130)); }

    /** Floor preview cell size: the whole floor fits the preview, up to 14px a block. */
    private int floorCell(int n) { return Math.max(1, Math.min(14, previewSize() / n)); }

    /** The floor or shape preview, which opens the floor patterns or the shape layers. */
    private boolean overPreview(double mx, double my) {
        int size = st.mode == Mode.FLOOR ? st.floorSize * floorCell(st.floorSize) : previewSize(), y = top + 78 + 11;
        return (st.mode == Mode.FLOOR || st.mode == Mode.SHAPE) && mx >= left && mx < left + size && my >= y && my < y + size;
    }

    private void drawPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = left + panelX(), y = top + 24, w = PANEL_W, h = H - 24 - 26;
        Style.box(g, x, y, w, h, Style.SURFACE, Style.SURFACE_BORDER);
        region("info panel", x, y, w, h);
        int tx = x + 5, ty = y + 5, dark = Style.TEXT;

        if (checklistOpen) {
            label(g, "Gather checklist", tx, ty, dark);
            ty += 14;
            List<Block> blocks = palette.placeable();
            int[] rowTops = checklistRowTops();
            for (int i = 0; i < blocks.size(); i++) {
                Block b = blocks.get(i);
                int ry = rowTops[i];
                boolean done = checked.contains(b.id());
                Style.box(g, tx, ry + 4, 9, 9, done ? Style.ACCENT : Style.SLOT, done ? Style.ACCENT_BORDER : Style.SLOT_BORDER);
                BlockItems.item(g, b.id(), tx + 12, ry, 1);
                int ly = ry + 1;
                for (String line : checklistLines(b)) { label(g, (done ? "§m" : "") + line, tx + 31, ly, done ? Style.DIM : dark); ly += 9; }
                if (!b.variants().isEmpty()) label(g, BlockItems.ellipsize(font, variantsText(b), CHECK_TEXT_W), tx + 31, ly, Style.MUTED);
            }
            return;
        }

        if (decorOpen) {
            label(g, "Decor ideas", tx, ty, dark);
            ty += 14;
            for (Decor.Idea idea : decor) {
                BlockItems.item(g, idea.id(), tx, ty, 1);
                label(g, BlockItems.ellipsize(font, idea.name(), w - 30), tx + 20, ty, dark);
                label(g, BlockItems.ellipsizeQuietly(font, idea.why(), w - 30), tx + 20, ty + 9, Style.MUTED);
                ty += DECOR_ROW;
            }
            return;
        }

        Block block = selected == 0 ? palette.base() : selectedSlot().block();
        BlockItems.item(g, block.id(), tx, ty, 1);
        List<String> nameLines = BlockItems.wrap(font, block.name(), w - 30, 2);
        for (int i = 0; i < nameLines.size(); i++) label(g, nameLines.get(i), tx + 20, ty + i * 9, dark);
        label(g, selected == 0 ? (palette.baseCounts() ? "Base" : "Reference") : selectedSlot().role().label(), tx + 20, ty + nameLines.size() * 9 + 1, Style.MUTED);
        ty += Math.max(24, nameLines.size() * 9 + 15);
        if (selected == 0) {
            paragraph(g, Component.literal(palette.baseCounts() ? "Your base block. Every other slot is scored against it."
                    : "Color reference only. It isn't a path block, so it isn't counted, given or placed."), tx, ty, w - 10, dark);
            return;
        }
        Slot s = selectedSlot();
        Score sc = s.score();
        Object[][] parts = {{"Theme fit", sc.theme()}, {"Lightness", sc.light()}, {"Hue harmony", sc.hue()}, {"Community", sc.community()},
                {"Mood: " + st.tone.label, sc.mood()}, {"Blends with anchor", sc.contrast()}, {"Off-theme block", sc.offTheme()}, {"Mixed finish", sc.finish()}};
        for (Object[] p : parts) {
            double v = (double) p[1];
            if (v == 0 && !p[0].equals("Theme fit") && !p[0].equals("Community")) continue;
            label(g, (String) p[0], tx, ty, dark);
            String num = String.format(Locale.ROOT, "%+.1f", v);
            label(g, num, x + w - 5 - font.width(num), ty, v >= 0 ? Style.GOOD : Style.BAD);
            ty += 10;
        }
        String total = String.format(Locale.ROOT, "%.1f", sc.total());
        g.fill(tx, ty, x + w - 5, ty + 1, Style.SURFACE_BORDER);
        label(g, "Total", tx, ty + 3, 0xFFFFFFFF);
        label(g, total, x + w - 5 - font.width(total), ty + 3, 0xFFFFFFFF);
        ty += 15;
        String rank = s.locked() ? "Locked: reroll keeps it" : "Pick " + (s.rank() + 1) + " of top " + Math.min(Generator.TOP_K, s.poolSize()) + " (" + s.poolSize() + " fit)";
        label(g, hoveredAlt != null ? hoveredAlt : rank, tx, ty, hoveredAlt != null ? Style.TEXT : Style.MUTED);
        ty += 11;
        // Swap options: the slot's other top picks, click one to use it.
        hoveredAlt = null;
        int ax = altX();
        label(g, "Swap:", tx, ty + 5, Style.MUTED);
        region("swap options", ax, ty, s.options().size() * (ALT + 2), ALT);
        for (int i = 0; i < s.options().size(); i++) {
            Block o = s.options().get(i);
            int bx = ax + i * (ALT + 2);
            boolean cur = o.id().equals(block.id()), over = mouseX >= bx && mouseX < bx + ALT && mouseY >= ty && mouseY < ty + ALT;
            BlockItems.slotFrame(g, bx, ty, 1, cur);
            BlockItems.item(g, o.id(), bx + 1, ty + 1, 1);
            if (cur) BlockItems.ring(g, bx, ty, ALT);
            if (over) { BlockItems.hover(g, bx, ty, ALT); hoveredAlt = cur ? o.name() + " (current)" : o.name(); }
        }
        ty += ALT + 4;
        int lines = (top + H - 46 - GAP - ty) / font.lineHeight;
        if (lines > 0) {
            int ly = ty;
            for (String line : BlockItems.wrap(font, "Tags: " + String.join(", ", block.tags().stream().sorted().toList()), w - 10, lines, true)) {
                label(g, line, tx, ly, Style.DIM);
                ly += font.lineHeight;
            }
        }
    }

    private static final int CHECK_TEXT_W = PANEL_W - 5 - 31 - 5, DECOR_ROW = 22;

    private int altX() { return left + panelX() + 5 + font.width("Swap:") + 6; }

    /** Top of the swap row in the info panel, mirroring {@link #drawPanel}'s layout. */
    private int altY() {
        Slot s = selectedSlot();
        int nameLines = BlockItems.wrap(font, s.block().name(), PANEL_W - 30, 2).size();
        int ty = top + 24 + 5 + Math.max(24, nameLines * 9 + 15);
        Score sc = s.score();
        double[] vals = {sc.theme(), sc.light(), sc.hue(), sc.community(), sc.mood(), sc.contrast(), sc.offTheme(), sc.finish()};
        for (int i = 0; i < vals.length; i++) if (vals[i] != 0 || i == 0 || i == 3) ty += 10;
        return ty + 15 + 11;
    }

    /** Name over up to two lines, so long names like "Cracked Polished Blackstone Bricks" aren't cut off. */
    private List<String> checklistLines(Block b) { return BlockItems.wrap(font, b.name(), CHECK_TEXT_W, 2); }

    private static String variantsText(Block b) {
        return "+ " + String.join(", ", b.variants().stream().map(v -> v.substring(v.lastIndexOf('_') + 1)).toList());
    }

    /** Top of each checklist row, plus one past the last; rows grow with wrapped names and the variants line. */
    private int[] checklistRowTops() {
        List<Block> blocks = palette.placeable();
        int[] tops = new int[blocks.size() + 1];
        tops[0] = top + 24 + 5 + 14;
        for (int i = 0; i < blocks.size(); i++) {
            int lines = checklistLines(blocks.get(i)).size() + (blocks.get(i).variants().isEmpty() ? 0 : 1);
            tops[i + 1] = tops[i] + Math.max(20, lines * 9 + 5);
        }
        return tops;
    }

    @Override
    protected boolean clicked(MouseButtonEvent e) {
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int b = boundaryAt(e.x(), e.y());
            if (b >= 0) { dragBoundary = b; return true; }
            int count = palette.slots().size() + 1;
            for (int i = 0; i < count; i++) {
                int x = left + i * SLOT_GAP, y = top + 24;
                if (e.x() >= x && e.x() < x + SLOT && e.y() >= y && e.y() < y + SLOT + 12) {
                    if (selected != i) popCell(i);
                    selected = i;
                    checklistOpen = false;
                    decorOpen = false;
                    BlockItems.playPlace(minecraft, i == 0 ? palette.base().id() : palette.slots().get(i - 1).block().id());
                    rebuildWidgets();
                    return true;
                }
            }
            Slot sel = selectedSlot();
            if (sel != null && !checklistOpen && !decorOpen) {
                int ay = altY(), ax = altX();
                for (int i = 0; i < sel.options().size(); i++) {
                    int bx = ax + i * (ALT + 2);
                    if (e.x() >= bx && e.x() < bx + ALT && e.y() >= ay && e.y() < ay + ALT) { swapTo(sel, sel.options().get(i)); return true; }
                }
            }
            if (overPreview(e.x(), e.y())) {
                minecraft.setScreenAndShow(st.mode == Mode.FLOOR ? new FloorScreen(st) : new ShapeScreen(st));
                return true;
            }
            if (checklistOpen) {
                int tx = left + panelX() + 5;
                List<Block> blocks = palette.placeable();
                int[] rowTops = checklistRowTops();
                for (int i = 0; i < blocks.size(); i++) {
                    if (e.x() >= tx && e.x() < left + W - 5 && e.y() >= rowTops[i] && e.y() < rowTops[i + 1]) {
                        String id = blocks.get(i).id();
                        if (!checked.remove(id)) checked.add(id);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    void auditSelect(int i) { selected = Math.min(i, palette.slots().size()); }
    void auditChecklist() { checklistOpen = true; }
    void auditDecor() { decorOpen = true; }
    void auditToast() { flash("Gave 6 blocks to the hotbar, 14 stairs/slabs/walls to inventory (3 items moved). Inventory full, 1 skipped."); }

    @Override
    public void onClose() {
        st.save();
        super.onClose();
    }
}
