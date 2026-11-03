package dev.jordan.palettepicker.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.jordan.palettepicker.core.BlockData;
import dev.jordan.palettepicker.core.Floors;
import dev.jordan.palettepicker.core.Generator;
import dev.jordan.palettepicker.core.Generator.EdgeStyle;
import dev.jordan.palettepicker.core.Generator.Request;
import dev.jordan.palettepicker.core.ShareCode;
import dev.jordan.palettepicker.core.Shapes;
import dev.jordan.palettepicker.core.Themes;
import dev.jordan.palettepicker.core.Themes.Mode;
import dev.jordan.palettepicker.core.Themes.Theme;
import dev.jordan.palettepicker.core.Themes.Tone;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Everything the screens share, persisted to config/palettepicker.json so settings and saved palettes survive restarts. */
public final class PickerState {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("palettepicker.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PickerState instance;

    // Persisted (Gson fields).
    Mode mode = Mode.BUILDING;
    String base = "spruce_log";
    Theme theme = Theme.RUSTIC;
    int size = 6;
    int pathWidth = 3;
    EdgeStyle edge = EdgeStyle.BLEND;
    Tone tone = Tone.ANY;
    double communityWeight = 1.0;
    String floorPattern = "checker";
    Floors.Shape floorShape = Floors.Shape.SQUARE;
    int floorSize = 9;
    boolean floorBorder = true;
    /** "Swap colors" rotation and "Vary" layout seed for the floor pattern. */
    int floorShift, floorSeed;
    Shapes.Kind shapeKind = Shapes.Kind.DOME;
    int shapeSize = 15, shapeHeight = 10;
    boolean shapeHollow = true, shapeThick;
    Shapes.Fill shapeFill = Shapes.Fill.GRADIENT;
    /** "Swap colors" and "Vary" for the shape, and the layer being looked at (0 = bottom). */
    int shapeShift, shapeSeed, shapeLayer;
    /** Shape screen: the 3D view (else the layer from above). */
    boolean shapeIn3d = true;
    Map<String, Integer> seeds = new HashMap<>();
    Map<String, String> locks = new HashMap<>();
    /** Path fill weights the player dragged (role → share); empty = the generator's defaults. */
    Map<String, Double> pathMix = new HashMap<>();
    List<Saved> saved = new ArrayList<>();

    /** A saved palette restores the exact request (locks hold every block, so it reproduces even after tuning changes). */
    record Saved(String title, Mode mode, String base, Theme theme, int size, int pathWidth, EdgeStyle edge, Map<String, String> locks, List<String> blocks,
                 Tone tone, String floorPattern, Floors.Shape floorShape, int floorSize, boolean floorBorder, int floorShift, int floorSeed,
                 Map<String, Double> pathMix, Shapes.Kind shapeKind, int shapeSize, int shapeHeight, boolean shapeHollow, boolean shapeThick,
                 Shapes.Fill shapeFill, int shapeShift, int shapeSeed) {}

    /** Most recent palette code posted in chat, for Paste code. */
    static volatile String lastChatCode;

    transient BlockData data;
    transient Generator generator;
    /** False for throwaway states (layout audit) so they never overwrite the player's config. */
    transient boolean persist = true;

    static PickerState get() {
        if (instance == null) {
            instance = load();
            // Opened in game, so models and textures are loaded and other mods' blocks can be sampled.
            instance.data = BlockData.fromClasspath().withExtra(ModdedBlocks.scan(net.minecraft.client.Minecraft.getInstance()));
            instance.generator = new Generator(instance.data);
            if (instance.data.get(instance.base).isEmpty()) instance.base = "spruce_log";
        }
        return instance;
    }

    /** A fresh state sharing {@code from}'s block data, that is never saved. */
    static PickerState scratch(PickerState from) {
        PickerState s = new PickerState();
        s.data = from.data;
        s.generator = from.generator;
        s.persist = false;
        return s;
    }

    Request request() { return new Request(mode, base, theme, size, edge, Map.copyOf(seeds), Map.copyOf(locks), communityWeight, tone); }

    Generator.Palette generate() { return generator.generate(request()); }

    Floors.Pattern pattern() { return Floors.pattern(floorPattern); }

    /** The palette laid out as the chosen floor pattern: a block id per cell, null outside the shape. */
    String[][] floor(Generator.Palette p) {
        List<Object> key = List.of(floorPattern, floorShape, floorSize, floorBorder, floorShift, floorSeed);
        if (p != floorFor || !key.equals(floorKey)) { floorFor = p; floorKey = key; floorCells = Floors.render(p, pattern(), floorShape, floorSize, floorBorder, floorShift, floorSeed); }
        return floorCells;
    }

    Shapes.Spec shapeSpec() { return new Shapes.Spec(shapeKind, shapeSize, shapeHeight, shapeHollow, shapeThick); }

    /** The palette laid over the chosen shape: a block id per cell, {@code [layer][row][column]}, null where it's empty. */
    String[][][] shape(Generator.Palette p) {
        List<Object> key = List.of(shapeSpec(), shapeFill, shapeShift, shapeSeed);
        if (p != shapeFor || !key.equals(shapeKey)) { shapeFor = p; shapeKey = key; shapeCells = Shapes.render(p, shapeSpec(), shapeFill, shapeShift, shapeSeed); }
        return shapeCells;
    }

    // Last floor and shape rendered: big ones take a while, and screens ask every frame.
    private transient Generator.Palette floorFor, shapeFor;
    private transient List<Object> floorKey, shapeKey;
    private transient String[][] floorCells;
    private transient String[][][] shapeCells;

    /** Changing base, theme, mode or sides invalidates rerolls and locks. */
    void resetPicks() { seeds.clear(); locks.clear(); }

    void restore(Saved s) {
        mode = s.mode(); base = s.base(); theme = s.theme(); size = s.size(); pathWidth = s.pathWidth(); edge = s.edge();
        seeds.clear(); locks.clear(); locks.putAll(s.locks());
        // Palettes saved before moods and floors have none of these.
        tone = s.tone() != null ? s.tone() : Tone.ANY;
        pathMix.clear();
        if (s.pathMix() != null) pathMix.putAll(s.pathMix());
        if (s.floorPattern() != null) {
            floorPattern = s.floorPattern(); floorShape = s.floorShape(); floorSize = Math.min(Floors.MAX_SIZE, s.floorSize()); floorBorder = s.floorBorder();
            floorShift = s.floorShift(); floorSeed = s.floorSeed();
        }
        if (s.shapeKind() != null) { // palettes saved before shapes have none
            shapeKind = s.shapeKind(); shapeSize = s.shapeSize(); shapeHeight = s.shapeHeight(); shapeHollow = s.shapeHollow(); shapeThick = s.shapeThick();
            shapeFill = s.shapeFill() != null ? s.shapeFill() : Shapes.Fill.GRADIENT; shapeShift = s.shapeShift(); shapeSeed = s.shapeSeed();
            shapeLayer = 0;
        }
    }

    /** The palette on screen as a {@link ShareCode}: every slot's block (as locks, like Save) plus the settings its mode uses. */
    String shareCode(Generator.Palette p) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("mode", mode.name().toLowerCase(Locale.ROOT)); f.put("base", base); f.put("theme", theme.name()); f.put("size", String.valueOf(size));
        f.put("tone", tone.name());
        // Setting keys never match a role id (walls, edge, mix, border…), which are written as-is.
        p.slots().forEach(s -> f.put(s.role().id(), s.block().id()));
        switch (mode) {
            case PATH -> {
                f.put("width", String.valueOf(pathWidth)); f.put("sides", edge.name());
                if (!pathMix.isEmpty()) f.put("weights", String.join(",", pathMix.entrySet().stream().map(e -> e.getKey() + ":" + Math.round(e.getValue() * 1000) / 1000.0).toList()));
            }
            case FLOOR -> {
                f.put("pattern", floorPattern); f.put("fshape", floorShape.name()); f.put("fsize", String.valueOf(floorSize)); f.put("ring", String.valueOf(floorBorder));
                f.put("fshift", String.valueOf(floorShift)); f.put("fseed", String.valueOf(floorSeed));
            }
            case SHAPE -> {
                f.put("kind", shapeKind.name()); f.put("ssize", String.valueOf(shapeSize)); f.put("height", String.valueOf(shapeHeight));
                f.put("hollow", String.valueOf(shapeHollow)); f.put("thick", String.valueOf(shapeThick)); f.put("fill", shapeFill.name());
                f.put("sshift", String.valueOf(shapeShift)); f.put("sseed", String.valueOf(shapeSeed));
            }
            default -> {}
        }
        return ShareCode.encode(f);
    }

    /** @param message null when it loaded cleanly, else a note (loaded) or what went wrong (not loaded, nothing changed) */
    record Applied(boolean loaded, String message) {}

    /** Loads a shared palette into this state. */
    Applied applyCode(String code) {
        Map<String, String> f;
        try { f = ShareCode.decode(code); } catch (IllegalArgumentException e) { return new Applied(false, "That isn't a palette code (they start with " + ShareCode.PREFIX + ")."); }
        try {
            Mode m = Mode.valueOf(f.get("mode").toUpperCase(Locale.ROOT));
            String b = f.get("base");
            if (b == null || data.get(b).isEmpty()) return new Applied(false, "This palette is built on " + b + ", which you don't have (another mod?).");
            Map<String, String> l = new HashMap<>();
            List<String> missing = new ArrayList<>();
            for (Themes.Role r : Themes.of(m).roles()) {
                String id = f.get(r.id());
                if (id == null) continue;
                if (data.get(id).isPresent()) l.put(r.id(), id); else missing.add(id);
            }
            Theme t = Theme.valueOf(f.get("theme"));
            int sz = Integer.parseInt(f.get("size"));
            Tone tn = Tone.valueOf(f.getOrDefault("tone", "ANY"));
            // Parse everything before changing anything, so a bad code leaves the current palette alone.
            int width = Integer.parseInt(f.getOrDefault("width", String.valueOf(pathWidth)));
            EdgeStyle e = EdgeStyle.valueOf(f.getOrDefault("sides", edge.name()));
            Map<String, Double> mix = new HashMap<>();
            if (f.containsKey("weights")) for (String kv : f.get("weights").split(",")) { String[] p = kv.split(":"); mix.put(p[0], Double.parseDouble(p[1])); }
            String pat = f.getOrDefault("pattern", floorPattern);
            Floors.Shape fs = Floors.Shape.valueOf(f.getOrDefault("fshape", floorShape.name()));
            int fsize = clamp(Integer.parseInt(f.getOrDefault("fsize", String.valueOf(floorSize))), Floors.MIN_SIZE, Floors.MAX_SIZE);
            Shapes.Kind k = Shapes.Kind.valueOf(f.getOrDefault("kind", shapeKind.name()));
            Shapes.Fill fill = Shapes.Fill.valueOf(f.getOrDefault("fill", shapeFill.name()));
            int ssize = clamp(Integer.parseInt(f.getOrDefault("ssize", String.valueOf(shapeSize))), Shapes.MIN_SIZE, Shapes.MAX_SIZE);
            int height = clamp(Integer.parseInt(f.getOrDefault("height", String.valueOf(shapeHeight))), 1, Shapes.MAX_SIZE);
            int fshift = Integer.parseInt(f.getOrDefault("fshift", String.valueOf(floorShift))), fseed = Integer.parseInt(f.getOrDefault("fseed", String.valueOf(floorSeed)));
            int sshift = Integer.parseInt(f.getOrDefault("sshift", String.valueOf(shapeShift))), sseed = Integer.parseInt(f.getOrDefault("sseed", String.valueOf(shapeSeed)));

            mode = m; base = b; theme = t; size = clamp(sz, 3, Themes.MAX_SIZE); tone = tn;
            seeds.clear(); locks.clear(); locks.putAll(l);
            pathWidth = width; edge = e; pathMix.clear(); pathMix.putAll(mix);
            floorPattern = pat; floorShape = fs; floorSize = fsize; floorBorder = Boolean.parseBoolean(f.getOrDefault("ring", String.valueOf(floorBorder)));
            floorShift = fshift; floorSeed = fseed;
            shapeKind = k; shapeSize = ssize; shapeHeight = height; shapeFill = fill; shapeShift = sshift; shapeSeed = sseed; shapeLayer = 0;
            shapeHollow = Boolean.parseBoolean(f.getOrDefault("hollow", String.valueOf(shapeHollow)));
            shapeThick = Boolean.parseBoolean(f.getOrDefault("thick", String.valueOf(shapeThick)));
            save();
            return new Applied(true, missing.isEmpty() ? null : "Loaded. You don't have " + String.join(", ", missing) + ", so stand-ins were picked.");
        } catch (RuntimeException e) {
            return new Applied(false, "That palette code is broken or from a newer version.");
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static PickerState load() {
        try {
            if (Files.exists(FILE)) {
                PickerState s = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), PickerState.class);
                if (s != null) {
                    if (s.seeds == null) s.seeds = new HashMap<>();
                    if (s.locks == null) s.locks = new HashMap<>();
                    if (s.pathMix == null) s.pathMix = new HashMap<>();
                    if (s.saved == null) s.saved = new ArrayList<>();
                    if (s.mode == null) s.mode = Mode.BUILDING;
                    if (s.theme == null) s.theme = Theme.RUSTIC;
                    if (s.edge == null) s.edge = EdgeStyle.BLEND;
                    if (s.tone == null) s.tone = Tone.ANY;
                    if (s.floorPattern == null) s.floorPattern = "checker";
                    if (s.floorShape == null) s.floorShape = Floors.Shape.SQUARE;
                    if (s.floorSize < Floors.MIN_SIZE) s.floorSize = 9;
                    s.floorSize = Math.min(s.floorSize, Floors.MAX_SIZE);
                    if (s.shapeKind == null) s.shapeKind = Shapes.Kind.DOME;
                    if (s.shapeFill == null) s.shapeFill = Shapes.Fill.GRADIENT;
                    if (s.shapeSize < Shapes.MIN_SIZE || s.shapeSize > Shapes.MAX_SIZE) s.shapeSize = 15;
                    if (s.shapeHeight < 1 || s.shapeHeight > Shapes.MAX_SIZE) s.shapeHeight = 10;
                    return s;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Corrupt or old config: start fresh rather than fail to open the screen.
        }
        return new PickerState();
    }

    void save() {
        if (!persist) return;
        try {
            Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Non-critical: settings just won't persist.
        }
    }
}
