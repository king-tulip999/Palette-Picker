package dev.jordan.palettepicker.client;

import dev.jordan.palettepicker.core.BlockData.Block;
import dev.jordan.palettepicker.core.Floors;
import dev.jordan.palettepicker.core.Generator.EdgeStyle;
import dev.jordan.palettepicker.core.Shapes;
import dev.jordan.palettepicker.core.Themes.Mode;
import dev.jordan.palettepicker.core.Themes.Theme;
import dev.jordan.palettepicker.core.Themes.Tone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Dev-only layout check. Each frame the picker screens record what they draw (text, widgets, regions); this flags
 * overlaps, anything outside the window, buttons closer than {@link PickerScreen#GAP}, and text cut short.
 * Run it with {@code gradlew runAudit} ({@code -Paudit=setup,shape} for just those screens): it opens a world, steps through screen states, writes
 * {@code run/palettepicker-audit.txt} and quits.
 */
final class LayoutAudit {
    static final boolean ENABLED = Boolean.getBoolean("palettepicker.audit");

    /** OVERLAY: floats over other content on purpose (toasts); only checked against the window bounds. */
    enum Kind { TEXT, WIDGET, REGION, OVERLAY }

    record Box(Kind kind, String what, int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }
        boolean contains(Box o) { return o.x >= x && o.y >= y && o.right() <= right() && o.bottom() <= bottom(); }
        boolean overlaps(Box o) { return x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom(); }
        @Override public String toString() { return kind.name().toLowerCase() + " \"" + what + "\" @" + x + "," + y + " " + w + "x" + h; }
    }

    /** The frame being recorded, or null when not auditing. */
    static LayoutAudit current;

    private final List<Box> boxes = new ArrayList<>();
    private final List<String> cuts = new ArrayList<>();

    void add(Kind kind, String what, int x, int y, int w, int h) { boxes.add(new Box(kind, what, x, y, w, h)); }

    /** Called when text had to be shortened to fit. */
    static void cut(String original) { if (current != null) current.cuts.add(original); }

    /** Issues for this frame, in window-relative coordinates (0,0 = top-left of the W x H layout). */
    List<String> check(int left, int top, int w, int h, int gap) {
        List<String> out = new ArrayList<>();
        List<Box> rel = boxes.stream().map(b -> new Box(b.kind(), b.what(), b.x() - left, b.y() - top, b.w(), b.h())).toList();
        for (Box b : rel)
            if (b.x() < 0 || b.y() < 0 || b.right() > w || b.bottom() > h) out.add("outside window: " + b);
        for (int i = 0; i < rel.size(); i++) {
            for (int j = i + 1; j < rel.size(); j++) {
                Box a = rel.get(i), b = rel.get(j);
                if (a.kind() == Kind.OVERLAY || b.kind() == Kind.OVERLAY) continue;
                // Text or a widget inside a region (grid, panel) is by design.
                if ((a.kind() == Kind.REGION && a.contains(b)) || (b.kind() == Kind.REGION && b.contains(a))) continue;
                if (a.overlaps(b)) { out.add("overlap: " + a + "  x  " + b); continue; }
                if (a.kind() == Kind.WIDGET && b.kind() == Kind.WIDGET) {
                    boolean sameRow = a.y() < b.bottom() && b.y() < a.bottom(), sameCol = a.x() < b.right() && b.x() < a.right();
                    int dx = Math.max(b.x() - a.right(), a.x() - b.right()), dy = Math.max(b.y() - a.bottom(), a.y() - b.bottom());
                    if (sameRow && dx < gap) out.add("buttons " + dx + "px apart: " + a + "  /  " + b);
                    if (sameCol && dy < gap) out.add("buttons " + dy + "px apart: " + a + "  /  " + b);
                }
            }
        }
        cuts.forEach(c -> out.add("cut off: \"" + c + "\""));
        return out;
    }

    // ---- Runner: steps through screen states once a world is loaded ----

    /** @param small lay the screen out at its minimum size instead of filling the game window */
    private record Scenario(String name, Supplier<Screen> screen, boolean small) {
        Scenario(String name, Supplier<Screen> screen) { this(name, screen, false); }
    }

    /** Screens lay out at their minimum size (see {@link PickerScreen#init}); the audit checks both sizes. */
    static boolean minimumSize;

    private static List<Scenario> scenarios;
    private static int index = -1, wait;
    private static final Map<String, List<String>> found = new LinkedHashMap<>(); // issue -> scenarios

    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (scenarios == null) { scenarios = build(); next(mc); return; }
        if (wait == 1 && shoot(scenarios.get(index).name())) // a frame has drawn: save it for a visual check
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "audit-" + scenarios.get(index).name().replace(' ', '_') + ".png", mc.gameRenderer.mainRenderTarget(), 1, c -> System.out.println("[palettepicker] " + c.getString()));
        if (--wait > 0) return;
        if (mc.gui.screen() instanceof PickerScreen s && s.lastIssues != null)
            for (String issue : s.lastIssues) found.computeIfAbsent(issue, k -> new ArrayList<>()).add(scenarios.get(index).name());
        next(mc);
    }

    /** First state of each kind gets a screenshot in run/screenshots. */
    private static final java.util.Set<String> shot = new java.util.HashSet<>();
    private static boolean shoot(String name) {
        if (name.startsWith("min ")) return false;
        String kind = name.startsWith("palette sel1 ") ? "palette " + name.split(" ")[2]
                : name.startsWith("shape ") || name.startsWith("shape+turned ") || name.startsWith("shape+layer ") ? String.join(" ", java.util.Arrays.copyOf(name.split(" "), name.startsWith("shape ") ? 3 : 4)) : name.split(" ")[0];
        return (kind.startsWith("palette ") || kind.equals("setup") || kind.equals("grid") || kind.equals("setup+search") || kind.equals("palette+mix") || kind.equals("floor") || kind.startsWith("shape ") || kind.startsWith("shape+turned ") || kind.startsWith("shape+layer ")) && shot.add(kind);
    }

    private static void next(Minecraft mc) {
        if (++index >= scenarios.size()) { finish(mc); return; }
        wait = 3;
        minimumSize = scenarios.get(index).small();
        mc.setScreenAndShow(scenarios.get(index).screen().get());
    }

    private static void finish(Minecraft mc) {
        List<String> lines = new ArrayList<>();
        lines.add("Palette Picker layout audit: " + scenarios.size() + " screen states, " + found.size() + " distinct issue(s).");
        lines.add("Coordinates are relative to the window's top-left. Creative player: " + mc.player.hasInfiniteMaterials());
        found.forEach((issue, where) -> {
            lines.add("");
            lines.add(issue);
            lines.add("  in " + where.size() + " state(s), e.g. " + String.join(" | ", where.subList(0, Math.min(3, where.size()))));
        });
        Path file = mc.gameDirectory.toPath().resolve("palettepicker-audit.txt");
        try { Files.write(file, lines, StandardCharsets.UTF_8); } catch (IOException e) { throw new RuntimeException(e); }
        System.out.println("[palettepicker] layout audit written to " + file.toAbsolutePath() + " (" + found.size() + " issues)");
        mc.stop();
    }

    private static List<Scenario> build() {
        PickerState real = PickerState.get();
        // Bases that stress the layout: the longest names, a short one, and blocks that aren't path blocks.
        List<String> bases = new ArrayList<>(real.data.blocks().stream()
                .sorted(Comparator.comparingInt((Block b) -> -Minecraft.getInstance().font.width(b.name()))).limit(3).map(Block::id).toList());
        bases.addAll(List.of("stone", "green_glazed_terracotta", "oak_leaves"));

        List<Scenario> out = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            for (String base : bases) {
                for (EdgeStyle edge : mode == Mode.PATH ? EdgeStyle.values() : new EdgeStyle[]{EdgeStyle.BLEND}) {
                    for (int size : new int[]{3, 6}) {
                        // Path: width. Floor and shape: size (smallest and largest).
                        for (int width : mode == Mode.PATH ? new int[]{3, 7} : mode == Mode.FLOOR ? new int[]{Floors.MIN_SIZE, Floors.MAX_SIZE}
                                : mode == Mode.SHAPE ? new int[]{Shapes.MIN_SIZE, Shapes.MAX_SIZE} : new int[]{3}) {
                            String name = mode + " " + base + " " + edge + " size" + size + (mode == Mode.BUILDING ? "" : " w" + width);
                            Supplier<PickerState> st = () -> scratch(real, mode, base, edge, size, width);
                            out.add(new Scenario("setup " + name, () -> new SetupScreen(st.get())));
                            out.add(new Scenario("setup+nosaved " + name, () -> { PickerState s = st.get(); s.saved.clear(); return new SetupScreen(s); }));
                            out.add(new Scenario("setup+toast " + name, () -> { SetupScreen s = new SetupScreen(st.get()); s.auditToast("That block isn't in the palette library."); return s; }));
                            out.add(new Scenario("setup+search " + name, () -> { SetupScreen s = new SetupScreen(st.get()); s.auditSearch("stone"); return s; }));
                            out.add(new Scenario("setup+nomatch " + name, () -> { SetupScreen s = new SetupScreen(st.get()); s.auditSearch("zzzz no such block here"); return s; }));
                            for (int sel = 0; sel <= 6; sel++) {
                                int pick = sel;
                                out.add(new Scenario("palette sel" + sel + " " + name, () -> { PaletteScreen p = new PaletteScreen(st.get()); p.auditSelect(pick); return p; }));
                            }
                            out.add(new Scenario("palette+checklist " + name, () -> { PaletteScreen p = new PaletteScreen(st.get()); p.auditChecklist(); return p; }));
                            out.add(new Scenario("palette+toast " + name, () -> { PaletteScreen p = new PaletteScreen(st.get()); p.auditToast(); return p; }));
                            out.add(new Scenario("palette+decor " + name, () -> { PaletteScreen p = new PaletteScreen(st.get()); p.auditDecor(); return p; }));
                            if (mode == Mode.PATH) // custom weights: the Reset button shows, and one segment is too thin for its percent
                                out.add(new Scenario("palette+mix " + name, () -> { PickerState s = st.get(); s.pathMix.putAll(Map.of("main", 0.9, "mix", 0.02, "mix2", 0.05)); return new PaletteScreen(s); }));
                            // The layer screen shows the mood, not the base, and sets its own sizes.
                            if (mode == Mode.SHAPE && base.equals(bases.get(0)) && width == Shapes.MIN_SIZE) {
                                for (Shapes.Kind kind : Shapes.Kind.values()) {
                                    for (int d : new int[]{Shapes.MIN_SIZE, 15, Shapes.MAX_SIZE}) {
                                        for (Shapes.Fill fill : d == 15 ? Shapes.Fill.values() : new Shapes.Fill[]{Shapes.Fill.GRADIENT}) {
                                            String sname = "shape " + kind + " " + d + " " + fill + " " + name;
                                            Supplier<PickerState> shaped = () -> { PickerState s = st.get(); s.shapeKind = kind; s.shapeSize = d; s.shapeHeight = d; s.shapeFill = fill; return s; };
                                            out.add(new Scenario(sname, () -> new ShapeScreen(shaped.get())));
                                            if (fill == Shapes.Fill.GRADIENT) {
                                                out.add(new Scenario("shape+top " + sname, () -> { ShapeScreen s = new ShapeScreen(shaped.get()); s.auditLayer(Integer.MAX_VALUE); return s; }));
                                                out.add(new Scenario("shape+solid " + sname, () -> { PickerState s = shaped.get(); s.shapeHollow = false; return new ShapeScreen(s); }));
                                                out.add(new Scenario("shape+turned " + sname, () -> { ShapeScreen sc = new ShapeScreen(shaped.get()); sc.auditTurn(215); return sc; }));
                                                out.add(new Scenario("shape+layer " + sname, () -> { PickerState s = shaped.get(); s.shapeIn3d = false; ShapeScreen sc = new ShapeScreen(s); sc.auditLayer(d / 2); return sc; }));
                                            }
                                        }
                                    }
                                }
                                out.add(new Scenario("shape+toast " + name, () -> { ShapeScreen s = new ShapeScreen(st.get()); s.auditToast(); return s; }));
                            }
                            if (mode == Mode.FLOOR) {
                                out.add(new Scenario("floor " + name, () -> new FloorScreen(st.get())));
                                out.add(new Scenario("floor+end " + name, () -> { FloorScreen f = new FloorScreen(st.get()); f.auditScrollEnd(); return f; }));
                            }
                        }
                    }
                }
            }
        }
        // The block grid opened on the leaves (tinted, see-through textures), for a screenshot.
        out.add(new Scenario("grid", () -> new SetupScreen(scratch(real, Mode.BUILDING, "oak_leaves", EdgeStyle.BLEND, 3, 3))));
        out.add(new Scenario("grid+delete", () -> { SetupScreen s = new SetupScreen(scratch(real, Mode.BUILDING, "oak_leaves", EdgeStyle.BLEND, 3, 3)); s.auditConfirmDelete(); return s; }));
        // Only some screens, when asked (gradlew runAudit -Paudit=setup,shape): setup, palette, floor, shape, grid.
        String only = System.getProperty("palettepicker.audit.only", "").trim();
        if (!only.isEmpty()) {
            List<String> screens = List.of(only.toLowerCase(java.util.Locale.ROOT).replace(" ", "").split(","));
            out.removeIf(sc -> !screens.contains(sc.name().split("[ +]")[0]));
        }
        // Every state again at the smallest layout, where things are tightest.
        List<Scenario> small = out.stream().map(sc -> new Scenario("min " + sc.name(), sc.screen(), true)).toList();
        out.addAll(small);
        return out;
    }

    /** A throwaway state that never writes the player's config. */
    private static PickerState scratch(PickerState real, Mode mode, String base, EdgeStyle edge, int size, int width) {
        PickerState s = PickerState.scratch(real);
        s.mode = mode; s.base = base; s.edge = edge; s.size = size; s.pathWidth = width; s.theme = Theme.RUSTIC;
        s.tone = Tone.MUTED; // the longest mood label
        if (mode == Mode.FLOOR) { s.floorSize = width; s.floorPattern = "scattered"; } // last pattern: the browser opens scrolled down
        if (mode == Mode.SHAPE) s.shapeSize = width;
        for (int i = 0; i < 12; i++) // enough saved palettes to need the scroll bar, with long titles
            s.saved.add(new PickerState.Saved("Building: Cracked Polished Blackstone Bricks, Weathered Chiseled Copper " + i, Mode.BUILDING, "stone",
                    Theme.RUSTIC, 6, 3, EdgeStyle.BLEND, Map.of(), List.of("stone", "oak_planks", "bricks"), Tone.ANY, null, null, 9, true, 0, 0, null, null, 0, 0, false, false, null, 0, 0));
        return s;
    }
}
