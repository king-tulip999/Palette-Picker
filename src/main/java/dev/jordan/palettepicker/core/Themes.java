package dev.jordan.palettepicker.core;

import dev.jordan.palettepicker.core.BlockData.Block;
import dev.jordan.palettepicker.core.BlockData.Face;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All tuning data: modes, slot roles, per-theme preferences. Change palettes here, then re-run GoldenPalettesTest. */
public final class Themes {
    private Themes() {}

    public static final int MAX_SIZE = 6;

    public enum Mode { BUILDING, PATH, FLOOR, SHAPE }

    /**
     * Mood (bloxelizer-style tone): pulls each slot's lightness target toward {@code light} by {@code pull} and scores
     * saturation by {@code sat} per unit. ANY changes nothing, so it leaves the approved palettes alone.
     */
    public enum Tone {
        ANY("Any mood", Double.NaN, 0, 0), PALE("Pale", .84, .6, -1.0), SOFT("Soft", .68, .4, -0.4),
        MUTED("Muted", Double.NaN, 0, -1.6), DEEP("Deep", .36, .35, 0.9), DARK("Dark", .2, .6, -0.3);
        public final String label;
        public final double light, pull, sat;
        Tone(String label, double light, double pull, double sat) { this.label = label; this.light = light; this.pull = pull; this.sat = sat; }
        public double target(double t) { return Double.isNaN(light) ? t : t + (light - t) * pull; }
    }

    public enum Theme {
        RUSTIC("Rustic"), MEDIEVAL("Medieval"), COZY("Cozy"), MODERN("Modern"),
        NORDIC("Nordic"), FANTASY("Fantasy"), INDUSTRIAL("Industrial"), COASTAL("Coastal");
        public final String label;
        Theme(String label) { this.label = label; }
    }

    /** Hard filter: block must have one of {@code need} (if non-empty) and none of {@code ban}. */
    public record Filter(Set<String> need, Set<String> ban) {
        public boolean allows(Block b) {
            return (need.isEmpty() || need.stream().anyMatch(b::has)) && ban.stream().noneMatch(b::has);
        }
    }

    /**
     * @param contrast   penalize blocks whose lightness nearly matches the anchor (it would blend in)
     * @param pop        reward saturation instead of hue harmony (accent-type slots)
     * @param allowColor exempt from the saturated-block penalty
     */
    public record Role(String id, String label, Filter filter, boolean contrast, boolean pop, boolean allowColor) {}

    /** @param target lightness 0-1, or NaN = match the anchor's lightness; @param anchor role id to compare against (null = base). */
    public record RoleSpec(List<String> tags, double target, String anchor) {}

    public record ModeDef(Mode mode, String name, String baseLabel, Face face, List<Role> roles, Map<Theme, Map<String, RoleSpec>> specs) {
        public RoleSpec spec(Theme t, String role) { return specs.get(t).get(role); }
    }

    static final Filter ANY = new Filter(Set.of(), Set.of());
    /** Structural slots (walls, roof, floor, trim): no lights, glass, leaves, furniture like barrels, or loose ground (gravel, sand). */
    static final Filter SOLID = new Filter(Set.of(), Set.of("light", "glass", "leaf", "utility", "path"));
    private static final Set<String> GROUND_NEED = Set.of("path", "earth", "sand", "rough", "stone", "brick", "mud", "moss", "concrete", "smooth", "tile", "deepslate");
    private static final Set<String> GROUND_ACCENT_BAN = Set.of("leaf", "light", "glass", "wool", "log", "plank", "metal", "odd", "precious");
    /** Path fill: a single layer of ground blocks. */
    public static final Filter GROUND = new Filter(GROUND_NEED, union(GROUND_ACCENT_BAN, "decor"));
    /** Sparse path stones may be decorative (chiseled) blocks; the fill may not. */
    static final Filter GROUND_ACCENT = new Filter(GROUND_NEED, GROUND_ACCENT_BAN);

    /** Cut / worked blocks; everything else on the ground (gravel, cobble, dirt, sand, moss, plain stone) is loose. */
    private static final Set<String> CUT = Set.of("brick", "tile", "smooth", "decor", "concrete", "quartz");
    public static boolean cut(Block b) { return CUT.stream().anyMatch(b::has); }
    /** Path fill roles that must share main's finish: stone bricks scattered through gravel look out of place. Edge may differ (a border reads as intentional). */
    public static final Set<String> SAME_FINISH_ROLES = Set.of("mix", "stones", "mix2", "patch");
    static final double MIXED_FINISH_PENALTY = 2.0;

    /** Materials a theme shouldn't use. Fantasy has none (it also allows "magic" odd blocks; see Generator.offTheme). */
    public static final Map<Theme, Set<String>> AVOID = new EnumMap<>(Map.of(
            Theme.RUSTIC, Set.of("concrete", "quartz", "metal"), Theme.MEDIEVAL, Set.of("concrete", "quartz", "metal"),
            Theme.COZY, Set.of("concrete", "quartz", "metal"), Theme.NORDIC, Set.of("concrete", "quartz", "metal", "sand"),
            Theme.MODERN, Set.of("mud", "moss", "rough"), Theme.FANTASY, Set.of(),
            Theme.INDUSTRIAL, Set.of("wool", "flower", "mud"), Theme.COASTAL, Set.of("mud", "deepslate", "metal")));

    public static final ModeDef BUILDING = new ModeDef(Mode.BUILDING, "Building", "Base", Face.SIDE, List.of(
            new Role("walls", "Walls", SOLID, true, false, false),
            new Role("roof", "Roof", SOLID, true, false, true),
            new Role("floor", "Floor", SOLID, false, false, false),
            new Role("trim", "Trim", SOLID, true, false, false),
            // Accent = the block that stands out: a light, a contrast material or a color pop.
            new Role("accent", "Accent", ANY, true, true, false)),
            specs(
                    Theme.RUSTIC, "walls", s("plank log stone", .42), "roof", s("plank tile", .25), "floor", s("plank stone", .45), "trim", s("rough stone", .48), "accent", s("moss light colored", .5),
                    Theme.MEDIEVAL, "walls", s("plaster white plank", .82), "roof", s("tile deepslate plank", .22), "floor", s("stone plank", .47), "trim", s("brick stone", .48), "accent", s("moss light brick", .5),
                    Theme.COZY, "walls", s("plank mud", .55), "roof", s("brick terracotta plank", .4), "floor", s("plank wool", .5), "trim", s("mud brick stone", .45), "accent", s("wool light colored", .65),
                    Theme.MODERN, "walls", s("concrete quartz smooth", .86), "roof", s("concrete smooth deepslate", .22), "floor", s("smooth stone", .62), "trim", s("concrete smooth plank", .3), "accent", s("glass light metal", .75),
                    Theme.NORDIC, "walls", s("plank log", .3), "roof", s("moss tile deepslate", .3), "floor", s("plank stone", .5), "trim", s("rough stone", .5), "accent", s("wool moss light", .6),
                    Theme.FANTASY, "walls", s("plaster white quartz", .78), "roof", s("magic colored tile", .4), "floor", s("stone plank", .5), "trim", s("log brick stone", .4), "accent", s("magic light colored", .65),
                    Theme.INDUSTRIAL, "walls", s("brick concrete deepslate", .38), "roof", s("metal deepslate concrete", .3), "floor", s("smooth stone rough", .45), "trim", s("metal deepslate", .35), "accent", s("metal light", .55),
                    Theme.COASTAL, "walls", s("plank white sand", .78), "roof", s("sea tile", .4), "floor", s("plank sand", .65), "trim", s("plank stone white", .55), "accent", s("sea light white", .65)));

    /** One block layer seen from above: every slot is a ground block scored on its top face. */
    public static final ModeDef PATH = new ModeDef(Mode.PATH, "Path", "Match", Face.TOP, List.of(
            new Role("main", "Main", GROUND, false, false, false),
            // Mix-ins match main's tone with a different texture; edge and stones must read against main.
            new Role("mix", "Mix-in", GROUND, false, false, false),
            new Role("edge", "Edge", new Filter(Set.of(), GROUND_ACCENT_BAN), true, false, false),
            new Role("stones", "Stones", GROUND_ACCENT, true, false, false),
            new Role("mix2", "Mix-in 2", GROUND, false, false, false),
            new Role("patch", "Patches", GROUND, false, false, false)),
            specs(
                    Theme.RUSTIC, "main", s("path earth", .45), "mix", m("rough earth"), "edge", s("rough stone moss", .45, "main"), "stones", s("stone rough", .5, "main"), "mix2", m("earth path rough"), "patch", s("moss earth", .36),
                    Theme.MEDIEVAL, "main", s("stone rough", .47), "mix", m("rough moss stone"), "edge", s("brick stone", .45, "main"), "stones", s("brick stone", .5, "main"), "mix2", m("stone rough"), "patch", s("moss", .4),
                    Theme.COZY, "main", s("path earth", .45), "mix", m("earth mud"), "edge", s("mud brick moss", .4, "main"), "stones", s("mud brick", .5, "main"), "mix2", m("earth path"), "patch", s("moss earth", .38),
                    Theme.MODERN, "main", s("smooth concrete", .62), "mix", m("smooth stone"), "edge", s("concrete deepslate smooth", .28, "main"), "stones", s("quartz white smooth", .85, "main"), "mix2", m("smooth concrete"), "patch", s("stone smooth", .45),
                    Theme.NORDIC, "main", s("rough path earth", .5), "mix", m("stone rough"), "edge", s("moss rough", .4, "main"), "stones", s("deepslate stone", .32, "main"), "mix2", m("rough earth"), "patch", s("moss earth", .35),
                    Theme.FANTASY, "main", s("stone path", .5), "mix", m("moss magic earth"), "edge", s("magic moss brick", .55, "main"), "stones", s("magic colored", .5, "main"), "mix2", m("stone earth"), "patch", s("moss magic", .4),
                    Theme.INDUSTRIAL, "main", s("rough deepslate stone", .4), "mix", m("deepslate rough"), "edge", s("deepslate smooth concrete", .35, "main"), "stones", s("smooth deepslate", .5, "main"), "mix2", m("rough stone"), "patch", s("deepslate rough", .3),
                    Theme.COASTAL, "main", s("sand path", .72), "mix", m("sand smooth"), "edge", s("stone smooth white", .6, "main"), "stones", s("sea stone", .5, "main"), "mix2", m("sand stone"), "patch", s("earth rough", .55)));

    /** Floor fill: no lights, glass, leaves, furniture or loose ground. Accent may be a light (froglight inlay). */
    static final Filter FLOOR_FILL = new Filter(Set.of(), Set.of("light", "glass", "leaf", "utility", "earth", "path"));
    static final Filter FLOOR_ACCENT = new Filter(Set.of(), Set.of("glass", "leaf", "utility", "path"));
    /** A base that can't be walked on (leaves, glass, furniture) is a color reference in floor mode, as in path mode. */
    public static final Filter FLOOR_BASE = new Filter(Set.of(), Set.of("glass", "leaf", "utility"));

    /**
     * Floor seen from above. The base is the main (field) block; "main" only exists when the base is a reference.
     * Ink order for patterns: main, second, accent, mix, detail; border frames the shape (see Floors).
     */
    public static final ModeDef FLOOR = new ModeDef(Mode.FLOOR, "Floor", "Main", Face.TOP, List.of(
            new Role("main", "Main", FLOOR_FILL, false, false, false),
            new Role("second", "Second", FLOOR_FILL, true, false, false),
            new Role("border", "Border", FLOOR_FILL, true, false, false),
            new Role("accent", "Accent", FLOOR_ACCENT, true, true, false),
            new Role("mix", "Mix-in", FLOOR_FILL, false, false, false),
            new Role("detail", "Detail", FLOOR_FILL, true, false, true)),
            specs(
                    Theme.RUSTIC, "main", s("plank stone", .48), "second", f("plank log stone", .34), "border", f("rough stone plank", .3), "accent", f("moss light brick", .6), "mix", m("plank stone"), "detail", f("stone brick rough", .62),
                    Theme.MEDIEVAL, "main", s("stone brick", .47), "second", f("stone tile brick", .32), "border", f("brick tile stone", .28), "accent", f("moss light", .55), "mix", m("stone rough"), "detail", f("plank smooth", .6),
                    Theme.COZY, "main", s("plank wool", .52), "second", f("plank mud", .4), "border", f("brick mud plank", .35), "accent", f("wool light colored", .65), "mix", m("plank"), "detail", f("terracotta brick", .45),
                    Theme.MODERN, "main", s("smooth concrete quartz", .8), "second", f("concrete smooth deepslate", .25), "border", f("smooth deepslate concrete", .2), "accent", f("glass light metal colored", .7), "mix", m("smooth concrete"), "detail", f("plank smooth", .5),
                    Theme.NORDIC, "main", s("plank stone", .5), "second", f("plank log", .3), "border", f("stone deepslate rough", .35), "accent", f("wool light moss", .7), "mix", m("plank"), "detail", f("white smooth", .82),
                    Theme.FANTASY, "main", s("stone smooth magic", .6), "second", f("magic colored tile", .45), "border", f("brick log stone", .35), "accent", f("magic light colored", .7), "mix", m("stone magic"), "detail", f("white plaster quartz", .85),
                    Theme.INDUSTRIAL, "main", s("smooth stone deepslate", .42), "second", f("metal deepslate", .3), "border", f("deepslate concrete metal", .22), "accent", f("metal light", .6), "mix", m("deepslate rough"), "detail", f("brick concrete", .5),
                    Theme.COASTAL, "main", s("plank sand smooth", .7), "second", f("sea stone tile", .45), "border", f("plank white stone", .55), "accent", f("sea light white", .7), "mix", m("sand plank"), "detail", f("white smooth", .85)));

    /**
     * Round builds (domes, towers, spheres). Same tuning as a building, renamed for a shell: main is the walls spec, second the
     * roof, shade the floor (it may sit close to main, which helps a gradient), trim and accent as they are.
     */
    public static final ModeDef SHAPE = new ModeDef(Mode.SHAPE, "Shape", "Base", Face.SIDE, List.of(
            new Role("main", "Main", SOLID, true, false, false),
            new Role("second", "Second", SOLID, true, false, true),
            new Role("trim", "Trim", SOLID, true, false, false),
            new Role("accent", "Accent", ANY, true, true, false),
            new Role("shade", "Shade", SOLID, false, false, false)),
            renamed(BUILDING.specs(), Map.of("main", "walls", "second", "roof", "trim", "trim", "accent", "accent", "shade", "floor")));

    public static ModeDef of(Mode mode) { return switch (mode) { case PATH -> PATH; case FLOOR -> FLOOR; case SHAPE -> SHAPE; default -> BUILDING; }; }

    /** Each theme's specs under new role ids: {@code from} maps new id to the old one. */
    private static Map<Theme, Map<String, RoleSpec>> renamed(Map<Theme, Map<String, RoleSpec>> specs, Map<String, String> from) {
        Map<Theme, Map<String, RoleSpec>> out = new EnumMap<>(Theme.class);
        specs.forEach((t, m) -> {
            Map<String, RoleSpec> r = new LinkedHashMap<>();
            from.forEach((to, old) -> r.put(to, m.get(old)));
            out.put(t, r);
        });
        return out;
    }

    private static RoleSpec s(String tags, double target) { return new RoleSpec(List.of(tags.split(" ")), target, null); }
    private static RoleSpec s(String tags, double target, String anchor) { return new RoleSpec(List.of(tags.split(" ")), target, anchor); }
    /** Floor slot, compared against the field (main, or the base when it is the field). */
    private static RoleSpec f(String tags, double target) { return s(tags, target, "main"); }
    /** Mix-in: same lightness as the main block. */
    private static RoleSpec m(String tags) { return new RoleSpec(List.of(tags.split(" ")), Double.NaN, "main"); }

    /** Varargs: Theme, then (roleId, RoleSpec) pairs, repeated. */
    private static Map<Theme, Map<String, RoleSpec>> specs(Object... a) {
        Map<Theme, Map<String, RoleSpec>> out = new EnumMap<>(Theme.class);
        Map<String, RoleSpec> cur = null;
        for (int i = 0; i < a.length; ) {
            if (a[i] instanceof Theme t) { out.put(t, cur = new LinkedHashMap<>()); i++; }
            else { cur.put((String) a[i], (RoleSpec) a[i + 1]); i += 2; }
        }
        return out;
    }

    private static Set<String> union(Set<String> s, String extra) {
        java.util.HashSet<String> h = new java.util.HashSet<>(s);
        h.add(extra);
        return Set.copyOf(h);
    }
}
