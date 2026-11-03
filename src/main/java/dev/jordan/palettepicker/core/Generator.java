package dev.jordan.palettepicker.core;

import dev.jordan.palettepicker.core.BlockData.Block;
import dev.jordan.palettepicker.core.BlockData.Face;
import dev.jordan.palettepicker.core.BlockData.Hsl;
import dev.jordan.palettepicker.core.Themes.Mode;
import dev.jordan.palettepicker.core.Themes.ModeDef;
import dev.jordan.palettepicker.core.Themes.Role;
import dev.jordan.palettepicker.core.Themes.RoleSpec;
import dev.jordan.palettepicker.core.Themes.Theme;
import dev.jordan.palettepicker.core.Themes.Tone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scores every block for each slot and picks from the top candidates. Pure Java: no Minecraft classes. */
public final class Generator {
    public static final int TOP_K = 5;

    public enum EdgeStyle { BLEND, SOLID }

    /**
     * @param seeds reroll counter per role id (0 = best match)
     * @param locks role id -> locked block id
     */
    public record Request(Mode mode, String baseId, Theme theme, int size, EdgeStyle edge, Map<String, Integer> seeds,
                          Map<String, String> locks, double communityWeight, Tone tone) {
        public Request withSeeds(Map<String, Integer> s) { return new Request(mode, baseId, theme, size, edge, s, locks, communityWeight, tone); }
        public Request withLocks(Map<String, String> l) { return new Request(mode, baseId, theme, size, edge, seeds, l, communityWeight, tone); }
    }

    /** Score split into parts so the UI can explain each pick. */
    public record Score(double theme, double light, double hue, double contrast, double offTheme, double finish, double community, double mood) {
        public double total() { return theme + light + hue + contrast + offTheme + finish + community + mood; }
    }

    /** @param options the top candidates for this slot, best first (what reroll picks among); the block is one of them unless locked */
    public record Slot(Role role, Block block, Score score, int rank, int poolSize, boolean locked, List<Block> options) {}

    /** @param baseCounts false when the base is only a color reference (e.g. leaves for a path) and not part of the palette. */
    public record Palette(Request request, Block base, boolean baseCounts, List<Slot> slots) {
        public List<Block> placeable() {
            List<Block> out = new ArrayList<>();
            if (baseCounts) out.add(base);
            slots.forEach(s -> out.add(s.block()));
            return out;
        }
        public Block block(String roleId) {
            return slots.stream().filter(s -> s.role().id().equals(roleId)).map(Slot::block).findFirst().orElse(null);
        }
    }

    private final BlockData data;

    public Generator(BlockData data) { this.data = data; }

    public static boolean baseCounts(Mode mode, Block base) {
        return switch (mode) { case PATH -> Themes.GROUND.allows(base); case FLOOR -> Themes.FLOOR_BASE.allows(base); default -> true; };
    }

    /** Grass-blend paths have no Edge slot; the next role takes its place. A floor's Main slot is the base itself when it counts. */
    public static List<Role> availableRoles(Mode mode, EdgeStyle edge, boolean baseCounts) {
        return Themes.of(mode).roles().stream()
                .filter(r -> !(mode == Mode.PATH && edge == EdgeStyle.BLEND && r.id().equals("edge")))
                .filter(r -> !(mode == Mode.FLOOR && baseCounts && r.id().equals("main"))).toList();
    }

    public static int maxSize(Mode mode, EdgeStyle edge, boolean baseCounts) {
        return Math.min(availableRoles(mode, edge, baseCounts).size() + (baseCounts ? 1 : 0), Themes.MAX_SIZE);
    }

    public Palette generate(Request r) {
        ModeDef md = Themes.of(r.mode());
        Block base = data.get(r.baseId()).orElseThrow(() -> new IllegalArgumentException("Unknown block " + r.baseId()));
        boolean counts = baseCounts(r.mode(), base);
        int roleCount = Math.min(r.size(), maxSize(r.mode(), r.edge(), counts)) - (counts ? 1 : 0);
        List<Role> roles = availableRoles(r.mode(), r.edge(), counts).subList(0, Math.max(0, roleCount));

        Set<String> used = new HashSet<>();
        used.add(base.id());
        roles.forEach(role -> { String l = r.locks().get(role.id()); if (l != null) used.add(l); });
        Map<String, Block> picked = new LinkedHashMap<>();
        List<Slot> slots = new ArrayList<>();

        for (int i = 0; i < roles.size(); i++) {
            Role role = roles.get(i);
            RoleSpec spec = md.spec(r.theme(), role.id());
            Block anchor = spec.anchor() != null && picked.containsKey(spec.anchor()) ? picked.get(spec.anchor()) : base;
            // Path blocks are seen from above; the base (a house block) is seen from the side.
            Hsl anchorHsl = anchor == base ? base.sideHsl() : anchor.hsl(md.face());
            Ctx ctx = new Ctx(r.mode(), md.face(), anchorHsl, spec.tags(), Double.isNaN(spec.target()) ? anchorHsl.l() : r.tone().target(spec.target()),
                    role, r.theme(), r.tone(), base, picked.get("main"), List.copyOf(picked.values()), r.communityWeight());

            String lockedId = r.locks().get(role.id());
            boolean locked = lockedId != null && data.get(lockedId).isPresent();
            // A locked block is still scored against the others, so the screen can offer them as swaps.
            List<Block> pool = data.blocks().stream().filter(b -> (!used.contains(b.id()) || b.id().equals(lockedId)) && role.filter().allows(b)).toList();
            if (pool.isEmpty()) pool = data.blocks().stream().filter(b -> !used.contains(b.id()) || b.id().equals(lockedId)).toList();
            record Cand(Block b, Score s) {}
            List<Cand> top = pool.stream().map(b -> new Cand(b, score(b, ctx)))
                    .sorted(Comparator.comparingDouble((Cand c) -> -c.s().total())).limit(TOP_K).toList();
            List<Block> options = top.stream().map(Cand::b).toList();
            Slot slot;
            if (locked) {
                Block b = data.get(lockedId).get();
                slot = new Slot(role, b, score(b, ctx), 0, 0, true, options);
            } else {
                int rank = pickIndex(r.seeds().getOrDefault(role.id(), 0), i, top.size());
                slot = new Slot(role, top.get(rank).b(), top.get(rank).s(), rank, pool.size(), false, options);
            }
            used.add(slot.block().id());
            picked.put(role.id(), slot.block());
            slots.add(slot);
        }
        return new Palette(r, base, counts, slots);
    }

    /** @param main the path's main block once picked (null in building mode or before it's picked) */
    private record Ctx(Mode mode, Face face, Hsl anchor, List<String> tags, double target, Role role, Theme theme, Tone tone, Block base,
                       Block main, List<Block> picked, double cw) {}

    private Score score(Block b, Ctx c) {
        Hsl h = b.hsl(c.face());
        // Best preferred tag counts fully (weights 3 / 2 / 1.2 by order); each extra preferred tag adds a little.
        double[] w = {3, 2, 1.2};
        double best = 0;
        int hits = 0;
        for (int i = 0; i < c.tags().size(); i++) if (b.has(c.tags().get(i))) { best = Math.max(best, w[Math.min(i, 2)]); hits++; }
        double theme = hits == 0 ? 0 : best + 0.4 * (hits - 1);

        double hue;
        if (h.s() < 0.2) hue = c.mode() == Mode.PATH ? 0.55 : 0.4; // neutrals pair with anything, even more so next to grass
        else if (c.role().pop()) hue = 0.8 * h.s();
        else if (c.anchor().s() < 0.2) hue = 0.2;
        else {
            double d = Math.abs(h.h() - c.anchor().h());
            hue = 0.6 * (1 - Math.min(d, 360 - d) / 180);
        }

        // Community pairs come from building palettes, so they guide path picks less.
        double community = (c.mode() == Mode.PATH ? 0.45 : 0.9) * data.affinity(c.base().id(), b.id());
        if (!c.picked().isEmpty()) community += 0.5 * c.picked().stream().mapToDouble(p -> data.affinity(p.id(), b.id())).average().orElse(0);

        return new Score(theme, -2.5 * Math.abs(h.l() - c.target()), hue,
                c.role().contrast() && Math.abs(h.l() - c.anchor().l()) < 0.06 ? -0.6 : 0,
                offTheme(b, h, c), finish(b, c), c.cw() * community, mood(h, c.tone()));
    }

    /** Mood: its saturation preference, plus a pull toward its lightness band strong enough to outweigh theme tags. */
    private static double mood(Hsl h, Tone t) {
        return t.sat * h.s() - (Double.isNaN(t.light) ? 0 : 1.5 * Math.abs(h.l() - t.light));
    }

    /** Off-theme materials, plus a penalty on saturated blocks outside "pop" slots (else red sand etc. wins on hue alone). */
    private static double offTheme(Block b, Hsl h, Ctx c) {
        double p = 0;
        if (b.has("glazed") && !(c.mode() == Mode.FLOOR && c.role().pop())) p -= 2.5; // glazed terracotta makes a good floor inlay
        if (b.has("odd") && !(c.theme() == Theme.FANTASY && b.has("magic"))) p -= 2.5;
        if (c.theme() == Theme.FANTASY) return p;
        if (Themes.AVOID.get(c.theme()).stream().anyMatch(b::has)) p -= 2.5;
        // A floor is a big area, so color reads stronger there: penalize it sooner.
        if (!c.role().pop() && !c.role().allowColor() && h.s() > (c.mode() == Mode.FLOOR ? 0.35 : 0.45)) p -= 1.2;
        return p;
    }

    /** Path fill that mixes cut blocks (bricks, tiles) with loose ones (gravel, cobble) is penalized; see Themes.CUT. */
    private static double finish(Block b, Ctx c) {
        if (c.main() == null || !Themes.SAME_FINISH_ROLES.contains(c.role().id())) return 0;
        return Themes.cut(b) == Themes.cut(c.main()) ? 0 : -Themes.MIXED_FINISH_PENALTY;
    }

    /** Deterministic pick among the top candidates so rerolls are repeatable; seed 0 is the best match. */
    /** Which of the top {@code n} the slot at {@code roleIndex} takes for a reroll {@code seed}; seed 0 = the best. */
    public static int pickIndex(int seed, int roleIndex, int n) {
        if (seed == 0 || n == 0) return 0;
        return (int) (((seed * 2654435761L + roleIndex * 97L) & 0xFFFFFFFFL) % n);
    }

    // ---------- path helpers ----------

    /** Share of path cells per ground role; main takes the rest. Outer rows get extra mix-in to soften the sides. */
    private static final List<Map.Entry<String, Double>> PATH_MIX = List.of(Map.entry("mix", 0.26), Map.entry("mix2", 0.12), Map.entry("stones", 0.10), Map.entry("patch", 0.08));

    public static List<Map.Entry<String, Double>> pathShares(Palette p, boolean outer) { return pathShares(p, outer, null); }

    /** Fill roles this palette has, main first, with their default shares (summing to 1). */
    public static Map<String, Double> defaultPathMix(Palette p) {
        Map<String, Double> out = new LinkedHashMap<>();
        double sum = 0;
        for (var e : PATH_MIX) if (p.block(e.getKey()) != null) sum += e.getValue();
        out.put("main", 1 - sum);
        for (var e : PATH_MIX) if (p.block(e.getKey()) != null) out.put(e.getKey(), e.getValue());
        return out;
    }

    /**
     * The fill shares with the player's own weights ({@code mix}: role → weight, any scale; missing roles keep their default),
     * normalized to 1. Outer rows move up to 0.14 from main to the first mix-in, if it has any share, to soften the sides.
     */
    public static List<Map.Entry<String, Double>> pathShares(Palette p, boolean outer, Map<String, Double> mix) {
        Map<String, Double> w = defaultPathMix(p);
        if (mix != null) w.replaceAll((role, d) -> Math.max(0, mix.getOrDefault(role, d)));
        double sum = w.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) { w.replaceAll((role, d) -> 0.0); w.put("main", 1.0); sum = 1; }
        double total = sum;
        w.replaceAll((role, d) -> d / total);
        if (outer && w.getOrDefault("mix", 0.0) > 0) {
            double move = Math.min(0.14, w.get("main"));
            w.put("main", w.get("main") - move);
            w.merge("mix", move, Double::sum);
        }
        // Mix-ins first, main last: the order the preview's random draw has always used.
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        w.forEach((role, d) -> { if (!role.equals("main")) out.add(Map.entry(role, d)); });
        out.add(Map.entry("main", w.get("main")));
        return out;
    }

    /** Block id the preview uses for the grass around a path. */
    public static final String GRASS = "grass_block";

    /** Top-down preview: rows = width + 4 (two grass rows each side), each cell a block id. */
    public static String[][] pathPreview(Palette p, int width, int cols) { return pathPreview(p, width, cols, null); }

    /** @param weights the player's fill weights, or null for the defaults; see {@link #pathShares(Palette, boolean, Map)} */
    public static String[][] pathPreview(Palette p, int width, int cols, Map<String, Double> weights) {
        boolean solid = p.request().edge() == EdgeStyle.SOLID && p.block("edge") != null;
        int seedSum = p.request().seeds().values().stream().mapToInt(Integer::intValue).sum();
        Rng rng = new Rng(1337 + seedSum * 7919);
        int rows = width + 4, top = 2, bot = top + width - 1;
        String[][] cells = new String[rows][cols];
        Block mix = p.block("mix") != null ? p.block("mix") : p.block("main");
        List<Map.Entry<String, Double>> inner = pathShares(p, false, weights), outerShares = pathShares(p, true, weights);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                double v = rng.next();
                boolean inPath = y >= top && y <= bot, beside = y == top - 1 || y == bot + 1;
                String id;
                if (inPath) {
                    boolean outer = !solid && (y == top || y == bot);
                    id = outer && v > 0.92 ? GRASS : groundAt(p, outer ? v / 0.92 : v, outer ? outerShares : inner);
                } else if (beside) {
                    id = solid ? p.block("edge").id() : v < 0.22 ? mix.id() : GRASS;
                } else id = GRASS;
                cells[y][x] = id;
            }
        }
        return cells;
    }

    private static String groundAt(Palette p, double v, List<Map.Entry<String, Double>> shares) {
        double acc = 0;
        for (var e : shares) {
            acc += e.getValue();
            if (v < acc) return p.block(e.getKey()).id();
        }
        return p.block("main").id();
    }

    /** Building preview markers for the openings; every other cell is a block id, or null for sky. */
    public static final String WINDOW = "#window", DOOR = "#door";
    public static final int HOUSE_W = 13, HOUSE_H = 12;

    /**
     * Front of a small gabled house, {@code [row][column]}: roof, base-block frame and beam, walls, trim foundation and sills,
     * accent lamp over the door and the floor block as a porch. Missing slots fall back (roof → walls, trim → base, accent → trim, floor → trim).
     */
    public static String[][] buildingPreview(Palette p) {
        Block base = p.base(), walls = or(p.block("walls"), base), roof = or(p.block("roof"), walls), trim = or(p.block("trim"), base),
                accent = or(p.block("accent"), trim), floor = or(p.block("floor"), trim);
        String[][] cells = new String[HOUSE_H][HOUSE_W];
        int mid = HOUSE_W / 2;
        for (int y = 0; y < HOUSE_H; y++) {
            for (int x = 0; x < HOUSE_W; x++) {
                int d = Math.abs(x - mid);
                Block b = null;
                String id = null;
                if (y <= 5) { // gable: a two-thick roof slope over a wall triangle
                    if (d == y || d == y + 1) b = roof;
                    else if (d < y) id = y == 3 && d == 0 ? WINDOW : walls.id();
                } else if (y == 11) {
                    if (d <= 6) b = floor;
                } else if (d <= 5) {
                    if (y == 6 || y == 10) b = y == 6 ? base : trim;   // beam, foundation
                    else if (d == 5) b = base;                         // corner posts
                    else if (d == 0) id = y == 7 ? accent.id() : DOOR; // lamp over the door
                    else if (d == 2 || d == 3) id = y == 9 ? trim.id() : WINDOW; // windows with a sill
                    else b = walls;
                }
                cells[y][x] = b != null ? b.id() : id;
            }
        }
        return cells;
    }

    private static Block or(Block a, Block b) { return a != null ? a : b; }

    /** mulberry32: small, fast, deterministic. */
    static final class Rng {
        private int a;
        Rng(int seed) { a = seed; }
        double next() {
            a += 0x6D2B79F5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t ^= t + (t ^ (t >>> 7)) * (61 | t);
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }
    }

    // ---------- mood, ramp ----------

    /** Hue (degrees) -> color word, by upper bound. */
    private static final double[] HUE_AT = {12, 24, 40, 52, 70, 100, 150, 175, 195, 225, 255, 285, 320, 345, 361};
    private static final String[] HUE_WORD = {"Crimson", "Ember", "Amber", "Honey", "Olive", "Moss", "Fern", "Jade", "Teal", "Azure", "Indigo", "Violet", "Plum", "Rose", "Crimson"};

    /**
     * Bloxelizer-style name, a tone word and a color word ("Soft Amber", "Dark Slate"). The tone is the chosen mood, or read off
     * the blocks' average lightness and saturation; the color is the saturation-weighted mean hue, or a grey word if there is none.
     */
    public static String moodName(Palette p) {
        Face face = Themes.of(p.request().mode()).face();
        List<Block> bs = p.placeable().isEmpty() ? List.of(p.base()) : p.placeable();
        double l = 0, s = 0, x = 0, y = 0;
        for (Block b : bs) {
            Hsl h = b.hsl(face);
            l += h.l();
            s += h.s();
            double w = h.s() * (1 - Math.abs(2 * h.l() - 1)); // chroma: pale or near-black blocks barely tint the name
            x += w * Math.cos(Math.toRadians(h.h()));
            y += w * Math.sin(Math.toRadians(h.h()));
        }
        int n = bs.size();
        l /= n;
        s /= n;
        Tone t = p.request().tone();
        String tone = t != null && t != Tone.ANY ? t.label : l > .72 ? "Pale" : l < .28 ? "Dark" : s < .22 ? "Muted" : l > .55 ? "Soft" : "Deep";
        if (Math.hypot(x, y) / n < 0.06) return tone + " " + (l > .72 ? "Ivory" : l > .52 ? "Ash" : l > .32 ? "Slate" : "Charcoal");
        double hue = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
        int i = 0;
        while (hue >= HUE_AT[i]) i++;
        return tone + " " + HUE_WORD[i];
    }

    /** Solid blocks from lightest to darkest: shade a wall light at the top to dark at the base, or fade a floor. */
    public static List<Block> ramp(Palette p) {
        Face face = Themes.of(p.request().mode()).face();
        return p.placeable().stream().filter(b -> !b.has("light") && !b.has("glass") && !b.has("leaf"))
                .sorted(Comparator.comparingDouble((Block b) -> -b.hsl(face).l())).toList();
    }

    // ---------- text ----------

    /** Short "where to use it" lines, as {label, text} pairs. */
    public static List<String[]> ideas(Palette p) { return ideas(p, null); }

    /** @param mix the player's path fill weights, or null for the defaults */
    public static List<String[]> ideas(Palette p, Map<String, Double> mix) {
        List<String[]> out = new ArrayList<>();
        String base = p.base().name();
        if (p.request().mode() == Mode.BUILDING) {
            out.add(new String[]{"House:", join(base + " frame and beams", n(p, "walls", " walls"), n(p, "roof", " roof"))});
            if (p.block("floor") != null) out.add(new String[]{"Inside:", join(n(p, "floor", " floor"), n(p, "accent", " for light and color"))});
            if (p.block("trim") != null) out.add(new String[]{"Details:", p.block("trim").name() + " foundation, sills and window frames."});
            List<Block> ramp = ramp(p);
            if (ramp.size() >= 3) out.add(new String[]{"Shade:", "light at the top to dark at the base: " + String.join(" → ", ramp.stream().map(Block::name).toList()) + "."});
        } else if (p.request().mode() == Mode.SHAPE) {
            out.add(new String[]{"Shell:", join(base + " base", n(p, "main", " shell"), n(p, "second", " mixed in"), n(p, "trim", " rings"), n(p, "accent", " for light and color"))});
            List<Block> ramp = ramp(p);
            if (ramp.size() >= 3) out.add(new String[]{"Shade:", "dark at the base to light at the top: " + String.join(" → ", ramp.reversed().stream().map(Block::name).toList()) + "."});
        } else if (p.request().mode() == Mode.FLOOR) {
            Block field = p.baseCounts() ? p.base() : p.block("main");
            out.add(new String[]{"Lay:", join(field.name() + " field", n(p, "second", " for the pattern"), n(p, "mix", " mixed into the field"))});
            if (p.block("border") != null || p.block("accent") != null)
                out.add(new String[]{"Edge:", join(n(p, "border", " border"), n(p, "accent", " inlays"), n(p, "detail", " details"))});
        } else {
            List<String> fill = new ArrayList<>();
            pathShares(p, false, mix).stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .forEach(e -> fill.add(p.block(e.getKey()).name() + " ~" + Math.round(e.getValue() * 100) + "%"));
            out.add(new String[]{"Fill:", String.join(", ", fill) + "."});
            boolean solid = p.request().edge() == EdgeStyle.SOLID && p.block("edge") != null;
            out.add(new String[]{"Sides:", solid ? p.block("edge").name() + " border on both sides."
                    : join("Grass meets the path", n(p, "mix", " scattered a block out"))});
        }
        return out;
    }

    private static String n(Palette p, String role, String suffix) { return p.block(role) == null ? null : p.block(role).name() + suffix; }

    private static String join(String... parts) {
        List<String> l = new ArrayList<>();
        for (String s : parts) if (s != null) l.add(s);
        return String.join(", ", l) + ".";
    }
}
