package dev.jordan.palettepicker.client;

import dev.jordan.palettepicker.core.Floors;
import dev.jordan.palettepicker.core.Generator.EdgeStyle;
import dev.jordan.palettepicker.core.Shapes;
import dev.jordan.palettepicker.core.Themes.Mode;
import dev.jordan.palettepicker.core.Themes.Theme;
import dev.jordan.palettepicker.core.Themes.Tone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Dev-only: screenshots of hand-picked picker states for the mod page. Run it with {@code gradlew runShowcase}: it opens
 * "New World" at 1920x1080, saves {@code run/screenshots/showcase-NN-name.png} for each state and quits.
 */
final class Showcase {
    static final boolean ENABLED = Boolean.getBoolean("palettepicker.showcase");

    private record Shot(String name, Supplier<Screen> screen) {}

    private static List<Shot> shots;
    private static int index = -1, wait;

    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (shots == null) { shots = build(); next(mc); return; }
        if (--wait > 0) return;
        String file = String.format("showcase-%02d-%s.png", index + 1, shots.get(index).name());
        Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1, c -> System.out.println("[palettepicker] " + c.getString()));
        next(mc);
    }

    private static void next(Minecraft mc) {
        if (++index >= shots.size()) { mc.stop(); return; }
        wait = 40; // textures loaded, slot pop animations finished
        mc.setScreenAndShow(shots.get(index).screen().get());
    }

    private static List<Shot> build() {
        PickerState real = PickerState.get();
        List<Shot> out = new ArrayList<>();
        out.add(new Shot("setup", () -> new SetupScreen(withSaved(real, state(real, Mode.BUILDING, "spruce_log", Theme.COZY, Tone.ANY, 5)))));
        out.add(new Shot("building", () -> { PaletteScreen p = new PaletteScreen(state(real, Mode.BUILDING, "spruce_log", Theme.RUSTIC, Tone.ANY, 6)); p.auditSelect(1); return p; }));
        out.add(new Shot("building-medieval", () -> { PaletteScreen p = new PaletteScreen(state(real, Mode.BUILDING, "stone_bricks", Theme.MEDIEVAL, Tone.DEEP, 5)); p.auditSelect(2); return p; }));
        out.add(new Shot("decor", () -> { PaletteScreen p = new PaletteScreen(state(real, Mode.BUILDING, "cherry_planks", Theme.FANTASY, Tone.SOFT, 5)); p.auditDecor(); return p; }));
        out.add(new Shot("path", () -> { PickerState s = state(real, Mode.PATH, "coarse_dirt", Theme.RUSTIC, Tone.ANY, 5); s.pathWidth = 5; PaletteScreen p = new PaletteScreen(s); p.auditSelect(1); return p; }));
        out.add(new Shot("floor", () -> { PickerState s = state(real, Mode.FLOOR, "bricks", Theme.COZY, Tone.ANY, 4); s.floorPattern = "star"; s.floorShape = Floors.Shape.CIRCLE; s.floorSize = 21; return new FloorScreen(s); }));
        out.add(new Shot("floor-wood", () -> { PickerState s = state(real, Mode.FLOOR, "dark_oak_planks", Theme.COZY, Tone.ANY, 3); s.floorPattern = "herringbone"; s.floorSize = 16; return new FloorScreen(s); }));
        out.add(new Shot("shape-dome", () -> { ShapeScreen sc = new ShapeScreen(shape(real, "deepslate_tiles", Theme.FANTASY, Shapes.Kind.DOME, 31, 16, Shapes.Fill.GRADIENT)); sc.auditTurn(30); return sc; }));
        out.add(new Shot("shape-pyramid", () -> { ShapeScreen sc = new ShapeScreen(shape(real, "sandstone", Theme.COASTAL, Shapes.Kind.PYRAMID, 31, 16, Shapes.Fill.GRADIENT)); sc.auditTurn(30); return sc; }));
        out.add(new Shot("shape-layer", () -> { PickerState s = shape(real, "prismarine_bricks", Theme.COASTAL, Shapes.Kind.SPHERE, 25, 25, Shapes.Fill.GRADIENT); s.shapeIn3d = false; s.shapeHollow = false; ShapeScreen sc = new ShapeScreen(s); sc.auditLayer(14); return sc; }));
        out.add(new Shot("search", () -> { SetupScreen s = new SetupScreen(withSaved(real, state(real, Mode.BUILDING, "cut_copper", Theme.INDUSTRIAL, Tone.ANY, 5))); s.auditSearch("copper"); return s; }));
        out.add(new Shot("checklist", () -> { PaletteScreen p = new PaletteScreen(state(real, Mode.BUILDING, "dark_oak_log", Theme.MEDIEVAL, Tone.ANY, 6)); p.auditChecklist(); return p; }));
        return out;
    }

    /** A throwaway state that never writes the player's config. */
    private static PickerState state(PickerState real, Mode mode, String base, Theme theme, Tone tone, int size) {
        PickerState s = PickerState.scratch(real);
        s.mode = mode; s.base = base; s.theme = theme; s.tone = tone; s.size = size; s.edge = EdgeStyle.BLEND;
        return s;
    }

    private static PickerState shape(PickerState real, String base, Theme theme, Shapes.Kind kind, int size, int height, Shapes.Fill fill) {
        PickerState s = state(real, Mode.SHAPE, base, theme, Tone.ANY, 4);
        s.shapeKind = kind; s.shapeSize = size; s.shapeHeight = height; s.shapeFill = fill;
        return s;
    }

    /** A few saved palettes, as a player would have them. */
    private static PickerState withSaved(PickerState real, PickerState s) {
        for (PickerState p : List.of(state(real, Mode.BUILDING, "dark_oak_log", Theme.NORDIC, Tone.ANY, 5), state(real, Mode.BUILDING, "sandstone", Theme.COASTAL, Tone.PALE, 4),
                state(real, Mode.PATH, "packed_mud", Theme.RUSTIC, Tone.ANY, 4), state(real, Mode.BUILDING, "polished_blackstone_bricks", Theme.FANTASY, Tone.DARK, 5),
                state(real, Mode.FLOOR, "calcite", Theme.MODERN, Tone.ANY, 3)))
            s.saved.add(PaletteScreen.snapshot(p, p.generate()));
        return s;
    }
}
