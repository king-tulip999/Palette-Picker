package dev.jordan.palettepicker.core;

import dev.jordan.palettepicker.core.BlockData.Block;
import dev.jordan.palettepicker.core.BlockData.Face;
import dev.jordan.palettepicker.core.Generator.Palette;
import dev.jordan.palettepicker.core.Themes.Mode;
import dev.jordan.palettepicker.core.Themes.Theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decor to go with a palette (bloxelizer-style): doors, trapdoors, fences and shelves in the palette's wood (or copper / iron),
 * a light and chain, and candles, carpet and flowers matched to its accent color. Ids are 26.3 items. Pure Java.
 */
public final class Decor {
    private Decor() {}

    /** @param why short reason, shown under the name */
    public record Idea(String id, String name, String why) {}

    private static final Pattern WOOD = Pattern.compile(
            "^(?:stripped_|yellow_|orange_|red_)?(dark_oak|pale_oak|oak|spruce|birch|jungle|acacia|mangrove|cherry|bamboo|crimson|warped|poplar)_(?:planks|log|stem|block|mosaic|leaves)$");
    private static final Pattern COPPER = Pattern.compile("^(exposed_|weathered_|oxidized_)?(?:cut_|chiseled_)?copper(?:_block|_grate|_bulb)?$");
    private static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped", "poplar");
    private static final List<String> DYES = List.of("white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink");
    /** Petal colors, 0xRRGGBB, picked from the textures by eye. */
    private static final Map<String, Integer> FLOWERS = new LinkedHashMap<>();
    static {
        FLOWERS.put("poppy", 0xC8221C); FLOWERS.put("red_tulip", 0xD12A28); FLOWERS.put("orange_tulip", 0xEB7A22); FLOWERS.put("torchflower", 0xD9732C);
        FLOWERS.put("dandelion", 0xF2D41F); FLOWERS.put("wildflowers", 0xF2E06B); FLOWERS.put("cornflower", 0x4C6FD9); FLOWERS.put("blue_orchid", 0x2BAAF0);
        FLOWERS.put("allium", 0xB27AE0); FLOWERS.put("pink_tulip", 0xE8A3C8); FLOWERS.put("pink_petals", 0xF5B4D2); FLOWERS.put("cactus_flower", 0xE36FA4);
        FLOWERS.put("oxeye_daisy", 0xE8EAD8); FLOWERS.put("lily_of_the_valley", 0xF0F0F0);
    }

    public static List<Idea> suggest(Palette p, BlockData data) {
        Mode mode = p.request().mode();
        Theme theme = p.request().theme();
        Face face = Themes.of(mode).face();
        List<Block> blocks = new ArrayList<>(p.placeable());
        if (!p.baseCounts()) blocks.add(0, p.base());

        // Wood: the first wooden block in the palette, else the planks closest in color to the base. Modded blocks (ns:id) are skipped: ideas are vanilla items.
        String wood = null, woodWhy = null;
        for (Block b : blocks) {
            Matcher m = WOOD.matcher(b.id().contains(":") ? "" : b.id());
            if (m.matches()) { wood = m.group(1); woodWhy = "matches " + b.name(); break; }
        }
        if (wood == null) {
            double best = Double.MAX_VALUE;
            for (String w : WOODS) {
                Block planks = data.get(w + "_planks").orElse(null);
                if (planks == null) continue;
                double d = dist(planks.color(face), p.base().color(face));
                if (d < best) { best = d; wood = w; }
            }
            woodWhy = "closest wood to " + p.base().name();
        }
        String copper = null, copperWhy = null;
        for (Block b : blocks) {
            Matcher m = COPPER.matcher(b.id().contains(":") ? "" : b.id());
            if (m.matches()) { copper = m.group(1) == null ? "" : m.group(1); copperWhy = "matches " + b.name(); break; }
        }
        boolean iron = copper == null && woodWhy.startsWith("closest") && (theme == Theme.MODERN || theme == Theme.INDUSTRIAL);

        // Color matches go off the most saturated block; an all-neutral palette gets plain candles and greenery.
        Block pop = null;
        for (Block b : blocks) if (b.hsl(face).s() > 0.3 && (pop == null || b.hsl(face).s() > pop.hsl(face).s())) pop = b;
        Block field = blocks.get(0);

        Idea door = copper != null ? idea(copper + "copper_door", copperWhy) : iron ? idea("iron_door", theme.label + " theme") : idea(wood + "_door", woodWhy);
        Idea trapdoor = copper != null ? idea(copper + "copper_trapdoor", copperWhy) : iron ? idea("iron_trapdoor", theme.label + " theme") : idea(wood + "_trapdoor", woodWhy);
        Idea light = copper != null ? idea(copper + "copper_lantern", copperWhy)
                : theme == Theme.FANTASY ? idea("soul_lantern", "Fantasy glow") : theme == Theme.MODERN ? idea("end_rod", "clean Modern light") : idea("lantern", "warm light");
        Idea chain = idea(copper != null ? copper + "copper_chain" : "iron_chain", "to hang the light");
        Idea candle = pop == null ? idea("candle", "plain, for a neutral palette") : idea(dye(pop, face, data) + "_candle", "color of " + pop.name());
        Idea carpet = idea(dye(pop != null ? pop : field, face, data) + "_carpet", "rug in " + (pop != null ? pop : field).name() + "'s color");
        Idea flower = pop == null ? idea(greenery(theme), theme.label + " greenery") : idea(flower(pop, face), "close to " + pop.name());
        Idea ground = idea(groundCover(theme), "softens the path's sides");

        List<Idea> out = switch (mode) {
            case BUILDING -> List.of(door, trapdoor, light, chain, candle, flower);
            case PATH -> List.of(idea(wood + "_fence", woodWhy), idea(wood + "_fence_gate", woodWhy), light, flower, ground);
            case FLOOR -> List.of(carpet, candle, light, idea(wood + "_shelf", woodWhy), trapdoor, flower);
            case SHAPE -> List.of(light, chain, trapdoor, door, candle, flower);
        };
        Map<String, Idea> byId = new LinkedHashMap<>(); // greenery and ground cover can be the same plant
        out.forEach(i -> byId.putIfAbsent(i.id(), i));
        return List.copyOf(byId.values());
    }

    private static Idea idea(String id, String why) { return new Idea(id, BlockData.titleCase(id), why); }

    private static String dye(Block b, Face face, BlockData data) {
        String best = "white";
        double bd = Double.MAX_VALUE;
        for (String d : DYES) {
            Block wool = data.get(d + "_wool").orElse(null);
            if (wool == null) continue;
            double dd = dist(wool.color(Face.SIDE), b.color(face));
            if (dd < bd) { bd = dd; best = d; }
        }
        return best;
    }

    private static String flower(Block b, Face face) {
        String best = "poppy";
        double bd = Double.MAX_VALUE;
        for (var e : FLOWERS.entrySet()) {
            double d = dist(e.getValue(), b.color(face));
            if (d < bd) { bd = d; best = e.getKey(); }
        }
        return best;
    }

    private static String greenery(Theme t) {
        return switch (t) {
            case RUSTIC, NORDIC -> "fern";
            case COZY -> "azalea";
            case FANTASY -> "spore_blossom";
            case COASTAL -> "sea_pickle";
            default -> "bush";
        };
    }

    private static String groundCover(Theme t) {
        return switch (t) {
            case RUSTIC, MEDIEVAL -> "leaf_litter";
            case NORDIC -> "fern";
            case COASTAL -> "short_dry_grass";
            case FANTASY -> "firefly_bush";
            case MODERN, INDUSTRIAL -> "bush";
            default -> "short_grass";
        };
    }

    /** Red-weighted RGB distance ("redmean"): close enough to perceived difference for picking a dye. */
    private static double dist(int a, int b) {
        int r1 = a >> 16 & 0xFF, g1 = a >> 8 & 0xFF, b1 = a & 0xFF, r2 = b >> 16 & 0xFF, g2 = b >> 8 & 0xFF, b2 = b & 0xFF;
        double rm = (r1 + r2) / 2.0;
        int dr = r1 - r2, dg = g1 - g2, db = b1 - b2;
        return Math.sqrt((2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db);
    }
}
