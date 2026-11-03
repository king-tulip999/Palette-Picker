package dev.jordan.palettepicker.core;

import dev.jordan.palettepicker.core.BlockData.Block;
import dev.jordan.palettepicker.core.Generator.Palette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shapes built layer by layer: circles, squares, spheres, domes, cylinders, boxes, cones and pyramids, solid or as a shell, with the palette laid over
 * them by a {@link Fill}. Cells are {@code [layer][row][column]}, layer 0 at the bottom. Pure Java: no Minecraft classes.
 */
public final class Shapes {
    private Shapes() {}

    public enum Kind {
        CIRCLE("Circle", false), SQUARE("Square", false), SPHERE("Sphere", false), DOME("Dome", false),
        CYLINDER("Cylinder", true), BOX("Box", true), CONE("Cone", true), PYRAMID("Pyramid", true);
        public final String label;
        /** Takes its layer count from the height setting. */
        public final boolean tall;
        Kind(String label, boolean tall) { this.label = label; this.tall = tall; }
        /** Open below: a dome, cone or pyramid is a roof, so its shell has no floor. */
        public boolean roof() { return this == DOME || this == CONE || this == PYRAMID; }
        /** Walls only within a layer (a tube open at both ends), else a shell all round. */
        boolean flat() { return this == CIRCLE || this == SQUARE || this == CYLINDER; }
    }

    public enum Fill {
        GRADIENT("Gradient", "Dark at the base to light at the top, dithered where two blocks meet. Swap colors flips it."),
        BANDS("Bands", "Level rings that take the palette blocks in turn."),
        SPECKLE("Speckle", "Mostly one block, with a second and the odd third scattered in. Vary reshuffles them."),
        TRIM("Trim rings", "One block, with the trim block on the bottom and top rings."),
        SOLID("Solid", "Just the first block. Swap colors picks another.");
        public final String label, about;
        Fill(String label, String about) { this.label = label; this.about = about; }
    }

    public static final int MIN_SIZE = 5, MAX_SIZE = 126;

    /**
     * @param size   diameter in blocks
     * @param height layers, for {@link Kind#tall} kinds only (a circle or square is 1 layer, a sphere {@code size}, a dome the top half)
     * @param thick  shell walls joined side to side instead of corner to corner: no gaps you can see through diagonally
     */
    public record Spec(Kind kind, int size, int height, boolean hollow, boolean thick) {
        public int layers() {
            return switch (kind) {
                case CIRCLE, SQUARE -> 1;
                case SPHERE -> size;
                case DOME -> size - size / 2;
                case CYLINDER, BOX, CONE, PYRAMID -> height;
            };
        }
    }

    /** Filled cells, {@code [layer][row][column]}. */
    public static boolean[][][] voxels(Spec s) {
        int d = s.size(), h = s.layers();
        boolean[][][] out = new boolean[h][d][d];
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < d; x++) {
                    if (!inside(s, x, y, z)) continue;
                    out[y][z][x] = !s.hollow() || (s.kind().flat() ? touchesOutside2(s, x, y, z) : touchesOutside3(s, x, y, z));
                }
            }
        }
        return out;
    }

    /**
     * Whether a cell is in the solid shape. Coordinates may lie outside the grid. A dome is tested as the whole sphere, and
     * cones and pyramids carry on below layer 0 at full width, so their shells stay open at the bottom.
     */
    static boolean inside(Spec s, int x, int y, int z) {
        int d = s.size();
        double c = (d - 1) / 2.0, r = d / 2.0, dx = x - c, dz = z - c;
        boolean inGrid = x >= 0 && x < d && z >= 0 && z < d;
        return switch (s.kind()) {
            case CIRCLE, CYLINDER -> dx * dx + dz * dz <= r * r;
            case SQUARE -> inGrid;
            case BOX -> inGrid && y >= 0 && y < s.height();
            case SPHERE, DOME -> {
                double dy = (s.kind() == Kind.DOME ? y + d / 2 : y) - c;
                yield dx * dx + dy * dy + dz * dz <= r * r;
            }
            case CONE, PYRAMID -> {
                if (y >= s.height()) yield false;
                // Radius shrinks evenly to the top; never below one cell (or the middle four, for an even size), so the tip shows.
                double ry = r * (s.height() - Math.max(0, y)) / s.height();
                if (s.kind() == Kind.CONE) { ry = Math.max(ry, d % 2 == 0 ? 0.71 : 0); yield dx * dx + dz * dz <= ry * ry; }
                ry = Math.max(ry, d % 2 == 0 ? 1 : 0.5);
                yield Math.max(Math.abs(dx), Math.abs(dz)) + 0.5 <= ry; // the whole cell within: even steps
            }
        };
    }

    /** Circle, square and cylinder walls: only neighbours in the same layer count, so a tube stays open at both ends. */
    private static boolean touchesOutside2(Spec s, int x, int y, int z) {
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++)
                if ((dx != 0 || dz != 0) && (s.thick() || dx == 0 || dz == 0) && !inside(s, x + dx, y, z + dz)) return true;
        return false;
    }

    private static boolean touchesOutside3(Spec s, int x, int y, int z) {
        for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    int moved = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (moved > 0 && (s.thick() || moved == 1) && !inside(s, x + dx, y + dy, z + dz)) return true;
                }
        return false;
    }

    /** Solid palette blocks, main / second / accent / trim / shade first (or a building's walls / roof / ...), rotated by {@code shift}. */
    static List<Block> order(Palette p, int shift) {
        List<Block> solids = Generator.ramp(p);
        if (solids.isEmpty()) solids = p.placeable();
        List<Block> out = new ArrayList<>();
        for (String role : List.of("main", "walls", "second", "roof", "accent", "trim", "shade", "floor")) {
            Block b = p.block(role);
            if (b != null && solids.contains(b) && !out.contains(b)) out.add(b);
        }
        for (Block b : solids) if (!out.contains(b)) out.add(b);
        List<Block> rotated = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) rotated.add(out.get(Math.floorMod(i + shift, out.size())));
        return rotated;
    }

    /** Block id per cell, {@code [layer][row][column]}; null where the shape is empty. */
    public static String[][][] render(Palette p, Spec s, Fill fill, int shift, int seed) {
        boolean[][][] v = voxels(s);
        int d = s.size(), h = s.layers();
        List<Block> o = order(p, shift);
        List<Block> ramp = Generator.ramp(p);
        if (ramp.isEmpty()) ramp = o;
        if (fill == Fill.GRADIENT && Math.floorMod(shift, 2) == 1) ramp = ramp.reversed();
        Block trim = p.block("trim") != null && o.contains(p.block("trim")) && o.get(0) != p.block("trim") ? p.block("trim") : o.get(1 % o.size());
        double c = (d - 1) / 2.0, r = d / 2.0;
        int bandH = Math.max(1, (int) Math.round((h == 1 ? r : h) / (2.0 * o.size())));

        String[][][] out = new String[h][d][d];
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < d; x++) {
                    if (!v[y][z][x]) continue;
                    // How far "up" the cell is, 0..1. A circle or square has one layer, so it goes from the rim (0) to the centre (1).
                    double dist = s.kind() == Kind.SQUARE ? Math.max(Math.abs(x - c), Math.abs(z - c)) + 0.5 : Math.hypot(x - c, z - c), t = h == 1 ? 1 - Math.min(1, dist / r) : y / (h - 1.0);
                    int level = h == 1 ? (int) ((r - dist) / bandH) : y / bandH;
                    Block b = switch (fill) {
                        case GRADIENT -> {
                            double pos = (1 - t) * (ramp.size() - 1);
                            int k = (int) Math.floor(pos);
                            if (pos - k > Floors.bayer(h == 1 ? x : x + z, h == 1 ? z : y)) k++;
                            yield ramp.get(Math.min(ramp.size() - 1, k));
                        }
                        case BANDS -> o.get(Math.floorMod(level, o.size()));
                        case SPECKLE -> {
                            double n = Floors.hash(x + y * 131, z, seed);
                            yield n < 0.05 ? o.get(2 % o.size()) : n < 0.2 ? o.get(1 % o.size()) : o.get(0);
                        }
                        case TRIM -> {
                            boolean ring = h == 1 ? touchesOutside2(new Spec(s.kind(), d, 1, true, false), x, y, z)
                                    : y == 0 || y == h - 1 || (s.kind() == Kind.SPHERE && y == h / 2);
                            yield ring ? trim : o.get(0);
                        }
                        case SOLID -> o.get(0);
                    };
                    out[y][z][x] = b.id();
                }
            }
        }
        return out;
    }

    /** How many of each block the whole shape uses, most first. */
    public static Map<String, Integer> counts(String[][][] cells) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String[][] layer : cells) Floors.counts(layer).forEach((id, n) -> m.merge(id, n, Integer::sum));
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(m.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        Map<String, Integer> out = new LinkedHashMap<>();
        sorted.forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** "1,868 (29 stacks + 12)" style amounts: stacks of 64, and shulker boxes (27 stacks) once there are enough. */
    public static String amount(int n) {
        String s = String.format(Locale.ROOT, "%,d", n);
        if (n < 64) return s;
        int stacks = n / 64, rest = n % 64;
        if (stacks >= 27) return s + " (" + String.format(Locale.ROOT, "%.1f", n / (64.0 * 27)) + " shulkers)";
        return s + " (" + stacks + (stacks == 1 ? " stack" : " stacks") + (rest > 0 ? " + " + rest : "") + ")";
    }
}
