package dev.jordan.palettepicker.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Block library: colors sampled from the client jar, derived tags, variants and community affinity (see tools/palette_data.py). */
public final class BlockData {
    public enum Face { SIDE, TOP }

    public record Hsl(double h, double s, double l) {
        static Hsl ofRgb(int rgb) {
            double r = (rgb >> 16 & 0xFF) / 255.0, g = (rgb >> 8 & 0xFF) / 255.0, b = (rgb & 0xFF) / 255.0;
            double mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), l = (mx + mn) / 2, d = mx - mn;
            if (d == 0) return new Hsl(0, 0, l);
            double s = d / (1 - Math.abs(2 * l - 1));
            double h = mx == r ? ((g - b) / d + 6) % 6 : mx == g ? (b - r) / d + 2 : (r - g) / d + 4;
            return new Hsl(h * 60, s, l);
        }
    }

    /** {@code side}/{@code top} are 0xRRGGBB; {@code variants} are stair/slab/wall block ids. */
    public record Block(String id, String name, int side, int top, Hsl sideHsl, Hsl topHsl, Set<String> tags, List<String> variants) {
        public boolean has(String tag) { return tags.contains(tag); }
        public Hsl hsl(Face face) { return face == Face.TOP ? topHsl : sideHsl; }
        public int color(Face face) { return face == Face.TOP ? top : side; }
    }

    private static final Map<Character, String> SUFFIX = Map.of('t', "stairs", 'h', "slab", 'w', "wall");
    private final List<Block> blocks;
    private final Map<String, Block> byId = new LinkedHashMap<>();
    private final Map<String, Map<String, Double>> affinity = new HashMap<>();

    private BlockData(List<Block> blocks, Map<String, Map<String, Double>> affinity) {
        this.blocks = List.copyOf(blocks);
        blocks.forEach(b -> byId.put(b.id(), b));
        this.affinity.putAll(affinity);
    }

    private BlockData(List<Block> blocks, String affinityText) {
        this(blocks, Map.of());
        // "i.j.v" in base 36: row indices into blocks.tsv, v = log-lift x 10.
        for (String t : affinityText.trim().split("\\s+")) {
            if (t.isEmpty()) continue;
            String[] p = t.split("\\.");
            String a = blocks.get(Integer.parseInt(p[0], 36)).id(), b = blocks.get(Integer.parseInt(p[1], 36)).id();
            double v = Integer.parseInt(p[2], 36) / 10.0;
            affinity.computeIfAbsent(a, k -> new HashMap<>()).put(b, v);
            affinity.computeIfAbsent(b, k -> new HashMap<>()).put(a, v);
        }
    }

    public static BlockData load(Reader blocksTsv, Reader affinityTxt) throws IOException {
        List<Block> list = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(blocksTsv)) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t", -1);
                int side = Integer.parseInt(c[1].substring(1), 16), top = Integer.parseInt(c[2].substring(1), 16);
                List<String> variants = new ArrayList<>();
                if (c.length > 5 && !c[5].isEmpty()) {
                    String[] v = c[5].split(":");
                    for (char k : v[1].toCharArray()) variants.add(v[0] + "_" + SUFFIX.get(k));
                }
                list.add(new Block(c[0], titleCase(c[0]), side, top, Hsl.ofRgb(side), Hsl.ofRgb(top), Set.of(c[4].split(" ")), List.copyOf(variants)));
            }
        }
        StringBuilder aff = new StringBuilder();
        try (BufferedReader r = new BufferedReader(affinityTxt)) {
            for (String line; (line = r.readLine()) != null; ) aff.append(line).append(' ');
        }
        return new BlockData(list, aff.toString());
    }

    /** This library plus {@code extra} (blocks from other mods: colors and name-based tags, no community affinity). Ids already present are skipped. */
    public BlockData withExtra(List<Block> extra) {
        List<Block> all = new ArrayList<>(blocks);
        extra.stream().filter(b -> !byId.containsKey(b.id())).forEach(all::add);
        return new BlockData(all, affinity);
    }

    /** A block that isn't in blocks.tsv (from another mod); tags come from {@link #tagsFor}. */
    public static Block block(String id, String name, int side, int top, double noise, List<String> variants) {
        return new Block(id, name, side, top, Hsl.ofRgb(side), Hsl.ofRgb(top), tagsFor(id, side, noise), List.copyOf(variants));
    }

    // Same rules as tags_for() in tools/palette_data.py (PaletteChecks keeps the two in step), for blocks added at runtime.
    private static final String[][] RULES = {
        {"_log$|_stem$|^bamboo_block$|^stripped_bamboo_block$", "log wood"},
        {"_planks$|bamboo_mosaic|^barrel$|^bookshelf$", "plank wood"},
        {"^(barrel|bookshelf)$", "utility"},
        {"^stripped_", "smooth"},
        {"_leaves$", "leaf"},
        {"flowering_azalea|cherry_leaves", "flower"},
        {"^spruce_leaves$", "conifer"},
        {"poplar_leaves", "colored"},
        {"^(pale_)?moss_block$", "moss earth"},
        {"^(dirt|coarse_dirt|rooted_dirt|dirt_path|mud|packed_mud|muddy_mangrove_roots|clay|gravel|sand|red_sand)$", "earth"},
        {"^(dirt_path|coarse_dirt|gravel|sand|red_sand)$", "path"},
        {"^(sand|red_sand)$|sandstone", "sand"},
        {"^(mud|packed_mud|mud_bricks)$", "mud"},
        {"brick", "brick"},
        {"tiles?$", "tile"},
        {"cobble|cracked|^gravel$|^coarse_dirt$", "rough"},
        {"mossy", "moss"},
        {"polished|smooth|^cut_|chiseled|pillar", "smooth"},
        {"^(stone|cobblestone|andesite|diorite|granite|calcite|tuff|dripstone_block|smooth_stone|cinnabar|sulfur)$|stone_bricks|deepslate"
                + "|blackstone|basalt|tuff|andesite|diorite|granite|cinnabar|sulfur|sandstone|^dead_.*coral", "stone"},
        {"deepslate", "deepslate"},
        {"concrete_powder", "concrete rough"},
        {"concrete$", "concrete smooth"},
        {"terracotta$", "terracotta tile"},
        {"glazed", "glazed odd"},
        {"wool$", "wool"},
        {"quartz", "quartz smooth"},
        {"prismarine", "sea stone"},
        {"^dark_prismarine$|^(cyan|light_blue)_(terracotta|concrete)$", "sea"},
        {"copper|^iron_block$|^gold_block$|^netherite_block$|^raw_.*_block$", "metal"},
        {"^(diamond|emerald|lapis|redstone|coal)_block$", "precious odd"},
        {"glass", "glass"},
        {"glowstone|sea_lantern|shroomlight|froglight|jack_o_lantern", "light"},
        {"nether|crimson|warped|nylium|wart|soul_soil|gilded|obsidian|end_stone|purpur|amethyst|^(brain|bubble|fire|horn|tube)_coral|resin|tinted|dried_kelp", "odd"},
        {"amethyst|purpur|end_stone|cherry|froglight|prismarine|sea_lantern", "magic"},
        {"^hay_block$", "farm rough"},
        {"^(bone_block|mushroom_stem)$", "plaster"},
        {"^chiseled_|^dead_.*coral", "decor"},
        {"^dead_.*coral", "odd"},
        {"stained_glass", "odd"},
    };
    private static final java.util.regex.Pattern[] RULE_PATTERNS = java.util.Arrays.stream(RULES).map(r -> java.util.regex.Pattern.compile(r[0]))
            .toArray(java.util.regex.Pattern[]::new);

    /** Tags from the block's name (a modded id's namespace is ignored), its side color and its texture noise (std dev of luminance, 0-1). */
    public static Set<String> tagsFor(String id, int side, double noise) {
        String name = id.substring(id.indexOf(':') + 1);
        Set<String> t = new java.util.TreeSet<>();
        for (int i = 0; i < RULES.length; i++) if (RULE_PATTERNS[i].matcher(name).find()) t.addAll(List.of(RULES[i][1].split(" ")));
        Hsl h = Hsl.ofRgb(side);
        if (h.l() > 0.8 && h.s() < 0.25) t.add("white");
        if (t.contains("white") && !t.contains("wood") && !t.contains("glass") && !t.contains("wool")) t.add("plaster");
        if (h.s() > 0.45 && !t.contains("wood") && !t.contains("leaf")) t.add("colored");
        // Measured texture contrast decides rough/smooth where the name doesn't.
        if (!t.contains("wood") && !t.contains("smooth") && noise > 0.09) t.add("rough");
        if (!t.contains("wood") && !t.contains("rough") && noise < 0.04) t.add("smooth");
        return Set.copyOf(t);
    }

    /** Loads the data shipped in the mod jar. */
    public static BlockData fromClasspath() {
        try (InputStream b = resource("blocks.tsv"); InputStream a = resource("affinity.txt")) {
            return load(new InputStreamReader(b, StandardCharsets.UTF_8), new InputStreamReader(a, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = BlockData.class.getResourceAsStream("/assets/palettepicker/data/" + name);
        if (in == null) throw new IOException("Missing palette data: " + name);
        return in;
    }

    /** Row as read from blocks.tsv: id, side color, noise and tags, for checking {@link #tagsFor} against the Python build. */
    record Row(String id, int side, double noise, Set<String> tags) {}

    static List<Row> rows() throws IOException {
        List<Row> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(resource("blocks.tsv"), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("	", -1);
                out.add(new Row(c[0], Integer.parseInt(c[1].substring(1), 16), Integer.parseInt(c[3]) / 1000.0, Set.of(c[4].split(" "))));
            }
        }
        return out;
    }

    static String titleCase(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("_")) sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return sb.toString();
    }

    public List<Block> blocks() { return blocks; }
    public Optional<Block> get(String id) { return Optional.ofNullable(byId.get(id)); }
    public double affinity(String a, String b) { return affinity.getOrDefault(a, Map.of()).getOrDefault(b, 0.0); }
}
