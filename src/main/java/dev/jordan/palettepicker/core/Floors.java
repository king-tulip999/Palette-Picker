package dev.jordan.palettepicker.core;

import dev.jordan.palettepicker.core.BlockData.Block;
import dev.jordan.palettepicker.core.Generator.Palette;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Floor patterns. Each pattern maps a cell to an ink (which palette block goes there); {@link #layout} clips it to a square,
 * circle or octagon and adds the border ring, {@link #render} turns inks into block ids. Pure Java: no Minecraft classes.
 */
public final class Floors {
    private Floors() {}

    public enum Shape {
        SQUARE("Square"), CIRCLE("Circle"), OCTAGON("Octagon");
        public final String label;
        Shape(String label) { this.label = label; }
    }

    /** Floor sizes the slider offers. Even floors have no centre cell: shapes and radial patterns centre between cells. */
    public static final int MIN_SIZE = 5, MAX_SIZE = 64;

    /** Inks a pattern draws with: the field, its main contrast, a sparing pop, a near-field texture and a second contrast. */
    public static final int MAIN = 0, SECOND = 1, ACCENT = 2, MIX = 3, DETAIL = 4;
    /** Layout values for cells outside the shape and for the border ring. */
    public static final int OUT = -1, FRAME = -2;
    private static final String[] INK_ROLE = {"main", "second", "accent", "mix", "detail"};
    /** A missing slot borrows from the closest one: second and mix -> main, accent and detail -> second. */
    private static final int[] FALLBACK = {MAIN, MAIN, SECOND, MAIN, SECOND};

    /**
     * One cell as a pattern sees it.
     *
     * @param u    columns from the centre (the centre cell is 0,0; u and v run -c..c)
     * @param v    rows from the centre, down is positive
     * @param c    half the floor size
     * @param e    rings in from the shape's edge, inside the border if there is one (0 = outermost patterned cell)
     * @param r    distance from the centre
     * @param a    angle in radians, 0 along +u
     * @param seed layout variation for random patterns
     */
    public record Cell(int u, int v, int c, int e, double r, double a, int seed) {
        /** 0..1, stable for this cell, seed and salt. */
        public double rnd(int salt) { return hash(u, v, seed * 31 + salt); }
    }

    public interface Ink { int at(Cell c); }

    /** @param random draws from {@link Cell#rnd}, so "Vary" gives a new layout */
    public record Pattern(String id, String name, String group, String about, boolean random, Ink ink) {}

    public static final List<String> GROUPS = List.of("Tiles", "Wood", "Geometric", "Radial", "Rugs", "Natural");

    public static final List<Pattern> PATTERNS = List.of(
            // ---- Tiles ----
            p("plain", "Plain", "Tiles", "One block wall to wall. Turn the border on to frame it.", c -> MAIN),
            p("checker", "Checker", "Tiles", "Alternating single blocks, the classic two-tone floor.", c -> odd(c.u + c.v) ? SECOND : MAIN),
            p("big_checker", "Big checker", "Tiles", "Checkerboard of 2x2 squares.", c -> odd(div(c.u, 2) + div(c.v, 2)) ? SECOND : MAIN),
            p("giant_checker", "Giant checker", "Tiles", "Checkerboard of 3x3 squares, centred on the middle.", c -> odd(div(c.u + 1, 3) + div(c.v + 1, 3)) ? SECOND : MAIN),
            p("grout", "Tile and grout", "Tiles", "3x3 tiles with a one-block grout line between them.", c -> mod(c.u + 2, 4) == 0 || mod(c.v + 2, 4) == 0 ? SECOND : MAIN),
            p("tile_dot", "Tile and dot", "Tiles", "3x3 tiles, grout lines, and an accent where the lines cross.",
                    c -> { boolean a = mod(c.u + 2, 4) == 0, b = mod(c.v + 2, 4) == 0; return a && b ? ACCENT : a || b ? SECOND : MAIN; }),
            p("windowpane", "Windowpane", "Tiles", "Small 2x2 panes in a grid of lines.", c -> mod(c.u, 3) == 0 || mod(c.v, 3) == 0 ? SECOND : MAIN),
            p("diamond_checker", "Diamond checker", "Tiles", "Checkerboard turned 45 degrees.",
                    c -> odd(div(c.u + c.v + 3, 6) + div(c.u - c.v + 3, 6)) ? SECOND : MAIN),
            p("polka", "Polka dots", "Tiles", "Two sets of dots on offset grids.",
                    c -> mod(c.u, 4) == 0 && mod(c.v, 4) == 0 ? ACCENT : mod(c.u, 4) == 2 && mod(c.v, 4) == 2 ? SECOND : MAIN),
            p("studded", "Studded", "Tiles", "Accent studs every third block.", c -> mod(c.u, 3) == 0 && mod(c.v, 3) == 0 ? ACCENT : MAIN),
            p("hopscotch", "Hopscotch", "Tiles", "Big 2x2 tiles around small square ones, the pinwheel tiling.", c -> mod(2 * c.u + c.v, 5) == 0 ? SECOND : MAIN),
            p("plus_tiles", "Plus tiles", "Tiles", "Repeating crosses with an accent heart.", c -> {
                int du = mod(c.u + 2, 5) - 2, dv = mod(c.v + 2, 5) - 2;
                return du == 0 && dv == 0 ? ACCENT : (du == 0 && Math.abs(dv) <= 1) || (dv == 0 && Math.abs(du) <= 1) ? SECOND : MAIN;
            }),
            p("rosettes", "Rosettes", "Tiles", "Little four-petal flowers on a grid.", c -> {
                int du = mod(c.u + 2, 4) - 2, dv = mod(c.v + 2, 4) - 2;
                return du == 0 && dv == 0 ? ACCENT : Math.abs(du) + Math.abs(dv) == 1 ? SECOND : du == -2 && dv == -2 ? MIX : MAIN;
            }),
            p("gingham", "Gingham", "Tiles", "Two-block bands that cross; the crossings go darker.", c -> {
                boolean a = odd(div(c.u, 2)), b = odd(div(c.v, 2));
                return a && b ? SECOND : a || b ? MIX : MAIN;
            }),
            p("plaid", "Plaid", "Tiles", "Wide bands crossing, with thin pinlines between.", c -> {
                boolean a = mod(c.u, 6) < 2, b = mod(c.v, 6) < 2;
                return a && b ? SECOND : a || b ? MIX : mod(c.u, 6) == 4 || mod(c.v, 6) == 4 ? DETAIL : MAIN;
            }),

            // ---- Wood ----
            r("plank_run", "Plank run", "Wood", "Boards of random length along each row, joints staggered like real floorboards.", c -> {
                int o = (int) (hash(0, c.v, c.seed) * 4), len = 3 + (int) (hash(1, c.v, c.seed) * 2);
                double h = hash(div(c.u + o, len), c.v, c.seed + 7);
                return h < 0.55 ? MAIN : h < 0.85 ? MIX : SECOND;
            }),
            p("boardwalk", "Boardwalk", "Wood", "Rows of boards in two tones, crossed by a joist every fifth block.",
                    c -> mod(c.u, 5) == 0 ? SECOND : odd(c.v) ? MIX : MAIN),
            p("running_bond", "Running bond", "Wood", "Two-long bricks, each row shifted half a brick.",
                    c -> odd(div(c.u + (odd(c.v) ? 1 : 0), 2)) ? SECOND : MAIN),
            p("stack_bond", "Stack bond", "Wood", "Three-long boards stacked in straight columns.", c -> odd(div(c.u + 1, 3) + c.v) ? SECOND : MAIN),
            p("basketweave", "Basketweave", "Wood", "2x2 blocks of boards, alternating across and down.", c -> {
                boolean across = !odd(div(c.u, 2) + div(c.v, 2));
                return across ? (odd(c.v) ? MIX : MAIN) : (odd(c.u) ? DETAIL : SECOND);
            }),
            p("parquet", "Parquet", "Wood", "3x3 squares of boards, turning each square.", c -> {
                boolean across = !odd(div(c.u + 1, 3) + div(c.v + 1, 3));
                return across ? (mod(c.v + 1, 3) == 1 ? MIX : MAIN) : (mod(c.u + 1, 3) == 1 ? DETAIL : SECOND);
            }),
            p("herringbone", "Herringbone", "Wood", "Two-long boards, across then down, stepping corner to corner. Best with a mix-in and detail slot.", c -> {
                // Across boards start where (u - v) % 4 == 0, down boards where it is 3: together they tile the floor exactly.
                boolean across = mod(c.u - c.v, 4) < 2;
                return across ? (odd(c.v) ? MIX : MAIN) : (odd(c.u) ? DETAIL : SECOND);
            }),
            p("diagonal_weave", "Diagonal weave", "Wood", "Short diagonal runs crossing over and under each other.", c -> {
                boolean a = mod(c.u - c.v, 4) < 2, b = mod(c.u + c.v, 4) < 2;
                return a ? (b ? MAIN : MIX) : (b ? SECOND : DETAIL);
            }),
            p("chevron", "Chevron", "Wood", "Columns of boards that slope up, then down, in V shapes.", c -> {
                int w = div(c.u, 4), t = odd(w) ? c.v + mod(c.u, 4) : c.v - mod(c.u, 4);
                return odd(div(t, 2)) ? SECOND : MAIN;
            }),
            r("deck", "Deck", "Wood", "Rows of boards in two tones with the odd dark knot.", c -> c.rnd(1) < 0.05 ? DETAIL : odd(c.v) ? MIX : MAIN),
            p("inlaid", "Inlaid boards", "Wood", "Plain boards with a thin inlay every fourth row.",
                    c -> mod(c.v, 4) == 0 ? (mod(c.u, 4) == 0 ? ACCENT : SECOND) : MAIN),

            // ---- Geometric ----
            p("stripes", "Stripes", "Geometric", "Single-block rows in two tones.", c -> odd(c.v) ? SECOND : MAIN),
            p("wide_stripes", "Wide stripes", "Geometric", "Three-block bands.", c -> odd(div(c.v + 1, 3)) ? SECOND : MAIN),
            p("pinstripe", "Pinstripe", "Geometric", "A thin line every fourth row.", c -> mod(c.v, 4) == 0 ? SECOND : MAIN),
            p("diagonal", "Diagonal stripes", "Geometric", "Two-block stripes running corner to corner.", c -> odd(div(c.u + c.v, 2)) ? SECOND : MAIN),
            p("zigzag", "Zigzag", "Geometric", "Stripes that bend back and forth.", c -> odd(div(c.v + Math.abs(mod(c.u, 4) - 2), 2)) ? SECOND : MAIN),
            p("arrow", "Arrow", "Geometric", "Stripes that bend once at the centre line, pointing one way.", c -> odd(div(c.v + Math.abs(c.u), 2)) ? SECOND : MAIN),
            p("crosshatch", "Crosshatch", "Geometric", "Two sets of diagonal lines, accent where they meet.", c -> {
                boolean a = mod(c.u + c.v, 4) == 0, b = mod(c.u - c.v, 4) == 0;
                return a && b ? ACCENT : a || b ? SECOND : MAIN;
            }),
            p("trellis", "Trellis", "Geometric", "Tight diagonal lattice, like a garden trellis.", c -> mod(c.u + c.v, 3) == 0 || mod(c.u - c.v, 3) == 0 ? SECOND : MAIN),
            p("argyle", "Argyle", "Geometric", "Diamonds in two tones crossed by thin accent lines.", c -> {
                int pp = c.u + c.v, q = c.u - c.v;
                if (mod(pp, 8) == 4 || mod(q, 8) == 4) return ACCENT;
                return odd(div(pp + 4, 8) + div(q + 4, 8)) ? SECOND : MAIN;
            }),
            p("waves", "Waves", "Geometric", "Bands that roll like water.",
                    c -> odd(div(c.v + (int) Math.round(1.4 * Math.sin(c.u * Math.PI / 4)), 2)) ? SECOND : MAIN),
            p("sawtooth", "Sawtooth", "Geometric", "Rows of stepped triangles.", c -> mod(c.u, 4) >= mod(c.v, 4) ? MAIN : SECOND),
            p("triangles", "Triangles", "Geometric", "4x4 squares split on the diagonal, flipping tone each square.", c -> {
                boolean upper = mod(c.u, 4) > mod(c.v, 4);
                return upper ^ odd(div(c.u, 4) + div(c.v, 4)) ? SECOND : MAIN;
            }),
            p("bowties", "Bowties", "Geometric", "Hourglass shapes in each 5x5 square.", c -> {
                int du = Math.abs(mod(c.u + 2, 5) - 2), dv = Math.abs(mod(c.v + 2, 5) - 2);
                return du > dv ? SECOND : MAIN;
            }),
            p("scales", "Scales", "Geometric", "Overlapping arcs, like fish scales or roof shingles.", c -> {
                // U-shaped scales 6 wide and 2 tall, each row shifted half a scale.
                int row = div(c.v, 2), lx = mod(c.u + (odd(row) ? 3 : 0), 6);
                return mod(c.v, 2) == 0 ? (lx == 0 || lx == 5 ? SECOND : MAIN) : (lx == 0 || lx == 5 ? MAIN : SECOND);
            }),
            r("maze", "Maze", "Geometric", "A real one-path maze in wall and floor blocks.", c -> {
                int x = c.u + c.c, y = c.v + c.c;
                if (!odd(x) && !odd(y)) return SECOND;
                if (odd(x) && odd(y)) return MAIN;
                // Binary-tree maze: every room opens north or west, so between-room cells are open if the room after them chose them.
                int last = 2 * c.c;
                if (!odd(x)) return x > 0 && x < last && opensWest(x + 1, y, c.seed) ? MAIN : SECOND;
                return y > 0 && y < last && !opensWest(x, y + 1, c.seed) ? MAIN : SECOND;
            }),
            p("fade", "Fade", "Geometric", "Dithers from one block to the other across the floor.",
                    c -> (c.u + c.c + 0.5) / (2 * c.c + 1) > bayer(c.u, c.v) ? SECOND : MAIN),
            p("three_fade", "Three-way fade", "Geometric", "Main to mix-in to second, dithered across the floor.", c -> {
                double t = 2 * (c.u + c.c + 0.5) / (2 * c.c + 1), b = bayer(c.u, c.v);
                return t < 1 ? (t > b ? MIX : MAIN) : (t - 1 > b ? SECOND : MIX);
            }),

            // ---- Radial ----
            p("rings", "Rings", "Radial", "Rings that follow the floor's shape inward.", c -> odd(c.e) ? SECOND : MAIN),
            p("ripples", "Ripples", "Radial", "Round ripples from the centre, whatever the shape.", c -> odd((int) Math.round(c.r)) ? SECOND : MAIN),
            p("target", "Target", "Radial", "Bands two blocks wide around an accent bullseye.",
                    c -> c.r < 0.5 ? ACCENT : mod((int) Math.round(c.r), 4) < 2 ? SECOND : MAIN),
            p("sunburst", "Sunburst", "Radial", "Sixteen rays out from the centre.",
                    c -> c.r < 0.5 ? ACCENT : odd((int) Math.floor(c.a / (Math.PI / 8))) ? SECOND : MAIN),
            p("swirl", "Swirl", "Radial", "Six arms twisting round the centre.",
                    c -> c.r < 0.5 ? ACCENT : odd((int) Math.floor((c.a + c.r * 0.45) / (Math.PI / 3))) ? SECOND : MAIN),
            p("spiral", "Spiral", "Radial", "One line winding out from the middle.",
                    c -> mod((int) Math.floor(c.r - c.a / (2 * Math.PI) * 3), 3) == 0 ? SECOND : MAIN),
            p("compass", "Compass rose", "Radial", "Eight-point star, long points on the axes, halves shaded.", c -> {
                double cos = Math.abs(Math.cos(2 * c.a)), sin = Math.abs(Math.sin(2 * c.a));
                double reach = c.c * Math.max(0.3 + 0.7 * Math.pow(cos, 3), 0.3 + 0.35 * Math.pow(sin, 3));
                if (c.r < 0.5) return ACCENT;
                return c.r <= reach ? (odd((int) Math.floor(c.a / (Math.PI / 4))) ? SECOND : DETAIL) : MAIN;
            }),
            p("star", "Star", "Radial", "A four-point star with curved sides.",
                    c -> c.r < 0.5 ? ACCENT : Math.pow(Math.abs(c.u), 0.6) + Math.pow(Math.abs(c.v), 0.6) <= Math.pow(c.c, 0.6) * 1.08 ? SECOND : MAIN),
            p("diamond", "Diamond rings", "Radial", "Diamonds inside diamonds.", c -> {
                int m = Math.abs(c.u) + Math.abs(c.v);
                return m == 0 ? ACCENT : odd(m) ? SECOND : MAIN;
            }),
            p("centrepiece", "Centrepiece", "Radial", "A medallion in the middle of a plain field.", c -> {
                int m = Math.abs(c.u) + Math.abs(c.v), ring = (int) Math.round(c.c * 0.55);
                if (m == 0) return ACCENT;
                if (m == 2 || (int) Math.round(c.r) == ring) return SECOND;
                return m < 2 ? DETAIL : MAIN;
            }),
            p("corner_fans", "Corner fans", "Radial", "Quarter circles fanning out of each corner.", c -> {
                double rc = Math.hypot(c.c - Math.abs(c.u), c.c - Math.abs(c.v));
                return rc <= c.c * 0.6 ? (odd((int) Math.round(rc)) ? SECOND : MIX) : MAIN;
            }),
            p("union", "Union", "Radial", "A cross with diagonals through it.",
                    c -> c.u == 0 || c.v == 0 ? SECOND : Math.abs(c.u) == Math.abs(c.v) ? ACCENT : MAIN),
            p("cross", "Crossroads", "Radial", "Two paths crossing at a square in the middle.", c -> {
                int w = c.c / 5;
                boolean a = Math.abs(c.u) <= w, b = Math.abs(c.v) <= w;
                return a && b ? ACCENT : a || b ? SECOND : MAIN;
            }),
            p("quarters", "Quarters", "Radial", "Four quarters in two tones, split by a line.",
                    c -> c.u == 0 || c.v == 0 ? DETAIL : (c.u < 0) == (c.v < 0) ? MAIN : SECOND),
            p("pinwheel", "Pinwheel", "Radial", "Four blades turning round the centre.", c -> {
                if (c.u == 0 && c.v == 0) return ACCENT;
                boolean steep = Math.abs(c.v) > Math.abs(c.u) || (Math.abs(c.v) == Math.abs(c.u) && c.v > 0);
                return steep ^ ((c.u >= 0) == (c.v >= 0)) ? SECOND : MAIN;
            }),
            p("flower", "Flower", "Radial", "Six petals round an accent centre.",
                    c -> c.r <= c.c * 0.2 ? ACCENT : c.r <= c.c * 0.95 * Math.abs(Math.cos(3 * c.a)) ? SECOND : MAIN),
            p("radial_fade", "Radial fade", "Radial", "Main in the middle, dithering to second at the edges.",
                    c -> c.r / (c.c + 0.5) > bayer(c.u, c.v) ? SECOND : MAIN),

            // ---- Rugs ----
            p("rug", "Rug", "Rugs", "A band just inside the edge and a thin accent line within it.",
                    c -> c.e == 1 ? SECOND : c.e == 3 ? ACCENT : MAIN),
            p("double_frame", "Double frame", "Rugs", "Two thin frames, one inside the other.", c -> c.e == 1 || c.e == 3 ? SECOND : MAIN),
            p("dentil", "Dentil band", "Rugs", "A toothed band around the edge.", c -> c.e == 1 ? (odd(c.u + c.v) ? SECOND : DETAIL) : MAIN),
            p("checker_edge", "Checker edge", "Rugs", "A checkered band two blocks deep around a plain field.",
                    c -> c.e <= 1 ? (odd(c.u + c.v) ? SECOND : MIX) : MAIN),
            p("runner", "Runner", "Rugs", "A long rug down the middle with edging.", c -> {
                int half = Math.max(1, c.c / 3);
                return Math.abs(c.v) == half ? SECOND : Math.abs(c.v) < half ? DETAIL : MAIN;
            }),
            p("inset", "Inset panel", "Rugs", "A framed checkered panel set into the middle.", c -> {
                int k = Math.max(Math.abs(c.u), Math.abs(c.v)), half = c.c / 2;
                return k == half ? SECOND : k < half ? (odd(c.u + c.v) ? DETAIL : MIX) : MAIN;
            }),
            p("cornerstones", "Cornerstones", "Rugs", "An edge band with an accent block at each corner.", c -> {
                boolean corner = Math.abs(c.u) >= c.c - 2 && Math.abs(c.v) >= c.c - 2;
                return corner && c.e <= 2 ? ACCENT : c.e == 1 ? SECOND : MAIN;
            }),
            p("medallion_rug", "Medallion rug", "Rugs", "Edge band plus a diamond medallion.", c -> {
                int m = Math.abs(c.u) + Math.abs(c.v);
                return c.e == 1 ? SECOND : m <= c.c / 2 ? (odd(m) ? ACCENT : DETAIL) : MAIN;
            }),

            // ---- Natural ----
            r("blend", "Blend", "Natural", "Random mix: mostly main, some mix-in and second, a little accent.", c -> {
                double h = c.rnd(0);
                return h < 0.6 ? MAIN : h < 0.82 ? MIX : h < 0.94 ? SECOND : ACCENT;
            }),
            r("speckle", "Speckle", "Natural", "Main with a light scatter of accent and mix-in.", c -> {
                double h = c.rnd(0);
                return h < 0.08 ? ACCENT : h < 0.2 ? MIX : MAIN;
            }),
            r("patches", "Patches", "Natural", "Soft blotches of mix-in and second, like worn stone.", c -> {
                double n = noise(c.u / 3.0, c.v / 3.0, c.seed);
                return n < 0.33 ? MIX : n > 0.72 ? SECOND : MAIN;
            }),
            r("clusters", "Clusters", "Natural", "Clumps of second ringed with mix-in.", c -> {
                double n = noise(c.u / 2.5, c.v / 2.5, c.seed + 3);
                return n < 0.3 ? SECOND : n < 0.45 ? MIX : MAIN;
            }),
            r("stepping", "Stepping stones", "Natural", "Round stones dotted over the field.", c -> {
                double d = nearest(c.u, c.v, 4, c.seed)[0];
                return d <= 1.3 ? SECOND : MAIN;
            }),
            r("flagstone", "Flagstone", "Natural", "Irregular stones with grout between them.", c -> {
                double[] n = nearest(c.u, c.v, 4, c.seed);
                if (n[1] - n[0] < 0.6) return SECOND;
                double h = hash((int) n[2], (int) n[3], c.seed + 11);
                return h < 0.5 ? MAIN : h < 0.8 ? MIX : DETAIL;
            }),
            r("weathered", "Weathered", "Natural", "Fine cracks and a little wear.", c -> {
                if (Math.abs(noise(c.u / 2.5, c.v / 2.5, c.seed + 5) - 0.5) < 0.06) return SECOND;
                return c.rnd(2) < 0.1 ? MIX : MAIN;
            }),
            r("worn_edges", "Worn edges", "Natural", "Mix-in creeping in from the walls, clean in the middle.",
                    c -> c.rnd(0) < 0.65 - c.e * 0.18 ? MIX : MAIN),
            r("worn_path", "Worn path", "Natural", "A worn track across the middle where people walk.", c -> {
                boolean track = Math.abs(c.v) <= Math.max(1, c.c / 3);
                return c.rnd(0) < (track ? 0.5 : 0.08) ? MIX : MAIN;
            }),
            r("scattered", "Scattered", "Natural", "The odd accent and detail block set at random.", c -> {
                double h = c.rnd(0);
                return h < 0.06 ? ACCENT : h < 0.16 ? DETAIL : MAIN;
            }));

    public static Pattern pattern(String id) {
        for (Pattern p : PATTERNS) if (p.id().equals(id)) return p;
        return PATTERNS.get(1); // checker
    }

    /** Ink per cell, {@code [row][column]}, n x n. Patterns count cells from n/2; distance and angle are from the true middle. */
    public static int[][] layout(Pattern p, Shape shape, int n, boolean border, int seed) {
        int c = n / 2;
        double mid = (n - 1) / 2.0;
        int[][] edge = edgeDistance(shape, n);
        int[][] out = new int[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int u = x - c, v = y - c, e = edge[y][x];
                if (e < 0) out[y][x] = OUT;
                else if (border && e == 0) out[y][x] = FRAME;
                else {
                    int ink = p.ink().at(new Cell(u, v, c, border ? e - 1 : e, Math.hypot(x - mid, y - mid), (Math.atan2(y - mid, x - mid) + 2 * Math.PI) % (2 * Math.PI), seed));
                    out[y][x] = Math.max(MAIN, Math.min(DETAIL, ink));
                }
            }
        }
        return out;
    }

    /** @param dx columns from the middle of an n-wide floor (a half for even n), @param dy rows */
    static boolean inside(Shape s, double dx, double dy, int n) {
        double half = (n - 1) / 2.0;
        return switch (s) {
            case SQUARE -> true;
            case CIRCLE -> dx * dx + dy * dy <= n * n / 4.0;
            case OCTAGON -> Math.abs(dx) + Math.abs(dy) <= half * 1.5;
        };
    }

    /** Steps (4-neighbour) to the nearest cell outside the shape, minus one; -1 outside. Rings therefore follow the shape. */
    private static int[][] edgeDistance(Shape shape, int n) {
        double mid = (n - 1) / 2.0;
        int[][] d = new int[n][n];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                d[y][x] = inside(shape, x - mid, y - mid, n) ? Integer.MAX_VALUE : -1;
            }
        }
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (d[y][x] < 0) continue;
                for (int[] s : steps) {
                    int nx = x + s[0], ny = y + s[1];
                    if (nx < 0 || ny < 0 || nx >= n || ny >= n || d[ny][nx] < 0) { d[y][x] = 0; queue.add(new int[]{x, y}); break; }
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] q = queue.poll();
            for (int[] s : steps) {
                int nx = q[0] + s[0], ny = q[1] + s[1];
                if (nx >= 0 && ny >= 0 && nx < n && ny < n && d[ny][nx] == Integer.MAX_VALUE) {
                    d[ny][nx] = d[q[1]][q[0]] + 1;
                    queue.add(new int[]{nx, ny});
                }
            }
        }
        return d;
    }

    /**
     * Palette block for each ink, after fallbacks. {@code shift} rotates which block takes which ink among the slots the palette
     * has ("Swap colors"); the border block stays on the frame.
     */
    public static Block[] inks(Palette p, int shift) {
        Block[] own = new Block[INK_ROLE.length];
        own[MAIN] = p.baseCounts() ? p.base() : p.block("main");
        for (int i = 1; i < own.length; i++) own[i] = p.block(INK_ROLE[i]);
        List<Integer> present = new ArrayList<>();
        for (int i = 0; i < own.length; i++) if (own[i] != null) present.add(i);
        Block[] out = new Block[own.length];
        for (int k = 0; k < present.size(); k++) out[present.get(k)] = own[present.get(Math.floorMod(k + shift, present.size()))];
        for (int i = 1; i < out.length; i++) if (out[i] == null) out[i] = out[FALLBACK[i]];
        return out;
    }

    /** Block id per cell, {@code [row][column]}; null outside the shape. The frame uses the border slot, or second without one. */
    public static String[][] render(Palette p, Pattern pattern, Shape shape, int n, boolean border, int shift, int seed) {
        Block[] inks = inks(p, shift);
        Block frame = p.block("border") != null ? p.block("border") : inks[SECOND];
        int[][] l = layout(pattern, shape, n, border, seed);
        String[][] out = new String[n][n];
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                out[y][x] = l[y][x] == OUT ? null : l[y][x] == FRAME ? frame.id() : inks[l[y][x]].id();
        return out;
    }

    /** How many of each block a rendered floor uses, most first. */
    public static Map<String, Integer> counts(String[][] cells) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String[] row : cells) for (String id : row) if (id != null) m.merge(id, 1, Integer::sum);
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(m.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        Map<String, Integer> out = new LinkedHashMap<>();
        sorted.forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    // ---------- helpers ----------

    private static Pattern p(String id, String name, String group, String about, Ink ink) { return new Pattern(id, name, group, about, false, ink); }
    private static Pattern r(String id, String name, String group, String about, Ink ink) { return new Pattern(id, name, group, about, true, ink); }

    private static boolean odd(int x) { return (x & 1) != 0; }
    private static int mod(int a, int m) { return Math.floorMod(a, m); }
    private static int div(int a, int m) { return Math.floorDiv(a, m); }

    /** 0..1, stable per (x, y, salt). */
    static double hash(int x, int y, int salt) {
        int h = x * 374761393 + y * 668265263 + salt * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
    }

    /** Smooth value noise, 0..1. */
    private static double noise(double x, double y, int salt) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0, sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
        double a = hash(x0, y0, salt), b = hash(x0 + 1, y0, salt), c = hash(x0, y0 + 1, salt), d = hash(x0 + 1, y0 + 1, salt);
        return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy;
    }

    /** Ordered dither threshold, 0..1. */
    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    static double bayer(int u, int v) { return (BAYER[mod(v, 4) * 4 + mod(u, 4)] + 0.5) / 16; }

    /** Jittered-grid points every {@code step} cells: {nearest distance, second nearest, nearest point's grid x, grid y}. */
    private static double[] nearest(int u, int v, int step, int seed) {
        int gx = div(u, step), gy = div(v, step);
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        int bx = 0, by = 0;
        for (int j = gy - 1; j <= gy + 1; j++) {
            for (int i = gx - 1; i <= gx + 1; i++) {
                double px = i * step + hash(i, j, seed) * (step - 1), py = j * step + hash(i, j, seed + 1) * (step - 1);
                double d = Math.hypot(u - px, v - py);
                if (d < d1) { d2 = d1; d1 = d; bx = i; by = j; }
                else if (d < d2) d2 = d;
            }
        }
        return new double[]{d1, d2, bx, by};
    }

    /** Maze room at (x, y), both odd: true = opens west, false = opens north. The first row and column have only one choice. */
    private static boolean opensWest(int x, int y, int seed) {
        if (y <= 1) return true;
        if (x <= 1) return false;
        return hash(x, y, seed + 17) < 0.5;
    }
}
