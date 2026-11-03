package dev.jordan.palettepicker.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.jordan.palettepicker.core.BlockData;
import dev.jordan.palettepicker.core.BlockData.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EntityBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Full-cube blocks from other mods, added to the library when the picker first opens: colors and texture noise sampled the way
 * tools/palette_data.py samples vanilla, tags from their names ({@link BlockData#tagsFor}). They have no community affinity.
 */
final class ModdedBlocks {
    private ModdedBlocks() {}

    private static final Logger LOG = LoggerFactory.getLogger(PalettePickerClient.MOD_ID);
    /** Not palette material, like palette_data.py's EXCLUDE: ores, technical blocks, machines that happen to be cubes. */
    private static final Pattern EXCLUDE = Pattern.compile("_ore$|^ore_|spawner|command_block|barrier|structure_|jigsaw|infested_|^waxed_|test_"
            + "|_ice$|^ice$|slime|honey|sponge|magma|furnace|crafting|machine|generator|controller|creative_");

    static List<Block> scan(Minecraft mc) {
        long start = System.nanoTime();
        Set<String> ids = new HashSet<>();
        BuiltInRegistries.BLOCK.keySet().forEach(k -> ids.add(BlockItems.blockId(k)));
        List<Block> out = new ArrayList<>();
        for (Identifier key : BuiltInRegistries.BLOCK.keySet()) {
            if (key.getNamespace().equals("minecraft") || EXCLUDE.matcher(key.getPath()).find()) continue;
            net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.getValue(key);
            if (block instanceof EntityBlock || block.asItem() == Items.AIR) continue;
            String id = key.toString();
            try {
                if (!BlockItems.isCube(id)) continue;
                Sample side = sample(mc, BlockItems.faces(mc, id, Direction.NORTH).getFirst()), top = sample(mc, BlockItems.faces(mc, id, Direction.UP).getFirst());
                if (side == null || top == null) continue;
                boolean seeThrough = key.getPath().contains("glass") || key.getPath().contains("leaves");
                if (side.transparent() > 0.5 && !seeThrough) continue;
                out.add(BlockData.block(id, block.getName().getString(), side.rgb(), top.rgb(), side.noise(), variants(key, ids)));
            } catch (RuntimeException e) {
                // A broken model or texture: leave that block out rather than fail to open the picker.
            }
        }
        LOG.info("Palette Picker: added {} blocks from other mods in {} ms", out.size(), (System.nanoTime() - start) / 1_000_000);
        return out;
    }

    /** @param rgb average of the opaque pixels, tinted; @param noise std dev of their luminance; @param transparent share of pixels not fully opaque */
    private record Sample(int rgb, double noise, double transparent) {}

    /** Reads the sprite's own PNG (first frame if animated), as the atlas keeps no CPU copy we can reach. */
    private static Sample sample(Minecraft mc, BlockItems.Face face) {
        Identifier name = face.sprite().contents().name();
        Optional<Resource> res = mc.getResourceManager().getResource(name.withPath(p -> "textures/" + p + ".png"));
        if (res.isEmpty()) return null;
        try (InputStream in = res.get().open(); NativeImage im = NativeImage.read(in)) {
            int n = Math.min(im.getWidth(), im.getHeight()), tint = face.tint();
            int tr = tint >> 16 & 0xFF, tg = tint >> 8 & 0xFF, tb = tint & 0xFF;
            long r = 0, g = 0, b = 0;
            int count = 0, notOpaque = 0;
            double sum = 0, sumSq = 0;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int argb = im.getPixel(x, y), a = argb >>> 24;
                    if (a < 255) notOpaque++;
                    if (a <= 128) continue;
                    int pr = (argb >> 16 & 0xFF) * tr / 255, pg = (argb >> 8 & 0xFF) * tg / 255, pb = (argb & 0xFF) * tb / 255;
                    r += pr; g += pg; b += pb; count++;
                    double lum = (0.2126 * pr + 0.7152 * pg + 0.0722 * pb) / 255;
                    sum += lum; sumSq += lum * lum;
                }
            }
            if (count == 0) return null;
            double mean = sum / count;
            int rgb = (int) Math.round((double) r / count) << 16 | (int) Math.round((double) g / count) << 8 | (int) Math.round((double) b / count);
            return new Sample(rgb, Math.sqrt(Math.max(0, sumSq / count - mean * mean)), notOpaque / (double) (n * n));
        } catch (IOException e) {
            return null;
        }
    }

    /** Stairs, slab and wall sharing one stem, as palette_data.py finds them: oak_planks → oak_stairs, stone_bricks → stone_brick_stairs. */
    private static List<String> variants(Identifier key, Set<String> ids) {
        String path = key.getPath(), ns = key.getNamespace() + ":";
        List<String> stems = new ArrayList<>(List.of(path));
        String[][] swaps = {{"_planks", ""}, {"_block", ""}, {"bricks", "brick"}, {"tiles", "tile"}};
        for (String[] s : swaps) if (path.endsWith(s[0])) stems.add(path.substring(0, path.length() - s[0].length()) + s[1]);
        String stem = null;
        List<String> out = new ArrayList<>();
        for (String kind : List.of("stairs", "slab", "wall")) {
            for (String s : stems) {
                if (!ids.contains(ns + s + "_" + kind)) continue;
                if (stem != null && !stem.equals(s)) return List.of(); // mixed stems: ambiguous, like the Python build
                stem = s;
                out.add(ns + s + "_" + kind);
                break;
            }
        }
        return out;
    }
}
