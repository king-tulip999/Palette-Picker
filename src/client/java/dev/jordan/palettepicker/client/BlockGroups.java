package dev.jordan.palettepicker.client;

import dev.jordan.palettepicker.core.BlockData.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Sorts the block grid into categories (wood, stone, bricks…) so blocks are easier to find. */
final class BlockGroups {
    private BlockGroups() {}

    /** In display order. */
    enum Group {
        WOOD("Wood"), STONE("Stone"), BRICKS("Bricks & tiles"), EARTH("Earth & nature"), LEAVES("Leaves"),
        TERRACOTTA("Terracotta"), CONCRETE("Concrete"), WOOL("Wool"), METAL("Metal & ores"), OCEAN("Ocean"),
        NETHER_END("Nether & End"), GLASS_LIGHT("Glass & lights"), OTHER("Other");
        final String label;
        Group(String label) { this.label = label; }
    }

    /** First match wins, so the order of the checks matters (e.g. sandstone is stone, not earth). */
    static Group of(Block b) {
        String id = b.id();
        if (b.has("glass") || b.has("light")) return Group.GLASS_LIGHT;
        if (id.contains("terracotta")) return Group.TERRACOTTA;
        if (b.has("concrete")) return Group.CONCRETE;
        if (b.has("wool")) return Group.WOOL;
        if (b.has("log") || b.has("plank") || b.has("wood")) return Group.WOOD;
        if (b.has("leaf")) return Group.LEAVES;
        if (b.has("sea") || id.contains("coral")) return Group.OCEAN;
        if (b.has("metal") || b.has("precious")) return Group.METAL;
        if (b.has("odd") || b.has("magic")) return Group.NETHER_END;
        if (b.has("earth") || b.has("mud") || b.has("moss") || b.has("farm") || (b.has("sand") && !b.has("stone"))) return Group.EARTH;
        if (b.has("brick") || b.has("tile")) return Group.BRICKS;
        if (b.has("stone") || b.has("deepslate") || b.has("rough") || b.has("smooth") || b.has("plaster") || b.has("quartz")) return Group.STONE;
        return Group.OTHER;
    }

    /** Variants share a family key: stripped_spruce_log, polished_andesite and cut_copper sort next to their plain block. */
    private static String family(String id) {
        return id.replaceAll("^(stripped|polished|chiseled|cracked|mossy|smooth|cut|cobbled|exposed|weathered|oxidized|dead|infested)_", "")
                .replaceAll("^(stripped|polished|chiseled|cracked|mossy|smooth|cut|cobbled)_", "");
    }

    static Map<Group, List<Block>> group(List<Block> blocks) {
        Map<Group, List<Block>> out = new EnumMap<>(Group.class);
        for (Block b : blocks) out.computeIfAbsent(of(b), g -> new ArrayList<>()).add(b);
        Comparator<Block> order = Comparator.comparing((Block b) -> family(b.id())).thenComparing(Block::id);
        out.values().forEach(l -> l.sort(order));
        return out;
    }
}
