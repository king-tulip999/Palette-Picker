package dev.jordan.palettepicker.client;

import dev.jordan.palettepicker.core.BlockData.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Minecraft-side helpers: item stacks for block ids, slot drawing, block textures and sounds, the held block, and giving a palette in creative. */
final class BlockItems {
    private BlockItems() {}

    static ItemStack stack(String blockId, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(id(blockId)), count);
    }

    /** Vanilla blocks go by their bare path ("stone"), other mods' by the full id ("create:andesite_casing"). */
    static Identifier id(String blockId) { return Identifier.parse(blockId); }

    static String blockId(Identifier key) { return key.getNamespace().equals("minecraft") ? key.getPath() : key.toString(); }

    /** Slot tile with the block's item drawn at {@code scale} (1 = 16px). */
    static void slot(GuiGraphicsExtractor g, String blockId, int x, int y, int scale, boolean selected) {
        slotFrame(g, x, y, scale, selected);
        item(g, blockId, x + 1, y + 1, scale);
    }

    /** The empty slot only, for when the item is drawn separately (e.g. popped). Selected = green border; add {@link #ring} for emphasis. */
    static void slotFrame(GuiGraphicsExtractor g, int x, int y, int scale, boolean selected) {
        int s = 16 * scale + 2;
        Style.box(g, x, y, s, s, Style.SLOT, selected ? Style.ACCENT_BORDER : Style.SLOT_BORDER);
    }

    /** Green ring just outside a selected slot of {@code size} px. Draw it after neighbouring slots so they don't cover it. */
    static void ring(GuiGraphicsExtractor g, int x, int y, int size) {
        g.fill(x - 1, y - 1, x + size + 1, y, Style.ACCENT_BORDER);
        g.fill(x - 1, y + size, x + size + 1, y + size + 1, Style.ACCENT_BORDER);
        g.fill(x - 1, y, x, y + size, Style.ACCENT_BORDER);
        g.fill(x + size, y, x + size + 1, y + size, Style.ACCENT_BORDER);
    }

    static void item(GuiGraphicsExtractor g, String blockId, int x, int y, int scale) { itemPopped(g, blockId, x, y, scale, 1); }

    /** Item at {@code scale} (1 = 16px) grown by {@code pop} around its centre; used for the pick "pop". Full blocks are drawn as {@link #cube}s. */
    static void itemPopped(GuiGraphicsExtractor g, String blockId, int x, int y, int scale, float pop) {
        g.pose().pushMatrix();
        g.pose().translate(x + 8 * scale, y + 8 * scale);
        g.pose().scale(scale * pop, scale * pop);
        if (!cube(g, blockId, -8, -8, 16)) g.item(stack(blockId, 1), -8, -8);
        g.pose().popMatrix();
    }

    /**
     * Vanilla rasterizes GUI items at the game's GUI scale, so the picker's own scaling (and 2x slots) blurs them. A full block is
     * drawn here instead as three sheared textured faces, which stay sharp at any size: same 30/45 degree view and face shading as
     * the inventory. Returns false (draws nothing) for anything that isn't a plain cube.
     */
    static boolean cube(GuiGraphicsExtractor g, String blockId, float x, float y, float size) {
        Cube c = cube(blockId).orElse(null);
        if (c == null) return false;
        float k = size / 16f;
        // Corners of the projected cube in a 16px box: top diamond L-T-R-C, then down to BL-B-BR.
        float lx = 0.93f, rx = 15.07f, cx = 8, ty = 0.13f, my = 3.67f, cy = 7.2f, by = 12.33f, bot = 15.87f;
        // See-through blocks (leaves, glass) first get the far faces, darker, as the inventory shows them through the gaps.
        // The hidden back corner is at (cx, ty + by - my).
        if (!c.back().isEmpty()) {
            float backY = ty + by - my;
            face(g, c.back().get(0), x, y, k, lx, by, cx, backY, cx, bot, 0.45f);  // bottom: origin BL, u to the back corner, v to B
            // Keep every quad's winding the same as the front faces: a mirrored one is culled and never shows.
            face(g, c.back().get(1), x, y, k, lx, my, cx, ty, lx, by, 0.55f);     // behind the right face: origin L, u to T, v to BL
            face(g, c.back().get(2), x, y, k, cx, ty, rx, my, cx, backY, 0.5f);   // behind the left face: origin T, u to R, v to the back corner
        }
        face(g, c.top(), x, y, k, lx, my, cx, ty, cx, cy, 1f);      // origin L, u to T, v to C
        face(g, c.left(), x, y, k, lx, my, cx, cy, lx, by, 0.8f);   // origin L, u to C, v to BL
        face(g, c.right(), x, y, k, cx, cy, rx, my, cx, bot, 0.62f); // origin C, u to R, v to B
        return true;
    }

    /** Maps a 16x16 sprite onto the parallelogram with origin (ox, oy), u-edge to (ux, uy) and v-edge to (vx, vy), in a 16px box. */
    private static void face(GuiGraphicsExtractor g, List<Face> layers, float x, float y, float k,
                             float ox, float oy, float ux, float uy, float vx, float vy, float shade) {
        g.pose().pushMatrix();
        g.pose().mul(new Matrix3x2f((ux - ox) * k / 16, (uy - oy) * k / 16, (vx - ox) * k / 16, (vy - oy) * k / 16, x + ox * k, y + oy * k));
        for (Face f : layers) g.blitSprite(RenderPipelines.GUI_TEXTURED, f.sprite(), 0, 0, 16, 16, shade(f.tint(), shade));
        g.pose().popMatrix();
    }

    static int shade(int argb, float s) {
        int r = (int) ((argb >> 16 & 0xFF) * s), gr = (int) ((argb >> 8 & 0xFF) * s), b = (int) ((argb & 0xFF) * s);
        return argb & 0xFF000000 | r << 16 | gr << 8 | b;
    }

    /** {@code back}: bottom, south and east faces for see-through blocks, else empty. */
    private record Cube(List<Face> top, List<Face> left, List<Face> right, List<List<Face>> back) {}
    private static final Map<String, Optional<Cube>> CUBES = new HashMap<>();

    /** Sprites change when resource packs reload; screens call this on init. */
    static void clearCache() { CUBES.clear(); }

    private static Optional<Cube> cube(String blockId) { return CUBES.computeIfAbsent(blockId, BlockItems::loadCube); }

    /** A plain full cube (a face on every side), so it can be a palette block. */
    static boolean isCube(String blockId) { return cube(blockId).isPresent(); }

    private static Optional<Cube> loadCube(String blockId) {
        Minecraft mc = Minecraft.getInstance();
        if (block(blockId).defaultBlockState().isAir()) return Optional.empty();
        List<Face> top = faces(mc, blockId, Direction.UP), left = faces(mc, blockId, Direction.NORTH), right = faces(mc, blockId, Direction.WEST);
        // A plain cube has a culled quad on every side; slabs, paths and the like don't.
        if (top.isEmpty() || left.isEmpty() || right.isEmpty() || faces(mc, blockId, Direction.DOWN).isEmpty()
                || faces(mc, blockId, Direction.SOUTH).isEmpty() || faces(mc, blockId, Direction.EAST).isEmpty()) return Optional.empty();
        List<List<Face>> back = block(blockId).defaultBlockState().canOcclude() ? List.of()
                : List.of(faces(mc, blockId, Direction.DOWN), faces(mc, blockId, Direction.SOUTH), faces(mc, blockId, Direction.EAST));
        return Optional.of(new Cube(top, left, right, back));
    }

    /** Scale for a pop that started {@code sinceMs} ago: 1.35 easing back to 1 over 220ms. */
    static float pop(long sinceMs) {
        if (sinceMs < 0 || sinceMs >= POP_MS) return 1;
        float t = 1 - sinceMs / (float) POP_MS;
        return 1 + 0.35f * t * t;
    }

    static final int POP_MS = 220;

    /** Light hover wash over a slot's inner area. */
    static void hover(GuiGraphicsExtractor g, int x, int y, int size) { g.fill(x + 1, y + 1, x + size - 1, y + size - 1, Style.HOVER); }

    /** Plays the block's own place sound, so picking one feels like setting it down. */
    static void playPlace(Minecraft mc, String blockId) {
        BlockState state = block(blockId).defaultBlockState();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(state.getSoundType().getPlaceSound(), 1.0f, 0.7f));
    }

    private static net.minecraft.world.level.block.Block block(String blockId) {
        return BuiltInRegistries.BLOCK.getValue(id(blockId));
    }

    /** A block's top face: its texture and the tint it's drawn with (grass and leaves are tinted). */
    record Face(TextureAtlasSprite sprite, int tint) {}

    /** Top face from the block's model. Look up once per screen: sprites change when resource packs reload. */
    static Face topFace(Minecraft mc, String blockId) {
        List<Face> f = facesOrUnculled(mc, blockId, Direction.UP);
        return f.isEmpty() ? new Face(model(mc, blockId).particleMaterial().sprite(), 0xFFFFFFFF) : f.get(0);
    }

    /** Front (north) face, every layer: grass and the like draw a tinted overlay over the plain side. */
    static List<Face> sideFace(Minecraft mc, String blockId) {
        List<Face> f = facesOrUnculled(mc, blockId, Direction.NORTH);
        return f.isEmpty() ? List.of(new Face(model(mc, blockId).particleMaterial().sprite(), 0xFFFFFFFF)) : f;
    }

    /** A block texture by name ("oak_door_top"), untinted; empty if the atlas doesn't have it. */
    static Optional<Face> texture(Minecraft mc, String name) {
        TextureAtlasSprite s = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(Identifier.withDefaultNamespace("block/" + name));
        return s.contents().name().equals(MissingTextureAtlasSprite.getLocation()) ? Optional.empty() : Optional.of(new Face(s, 0xFFFFFFFF));
    }

    /** Culled quads on that side, else the unculled ones facing it: a block a bit short of a full cube (dirt path, farmland, mud) has an unculled top. */
    private static List<Face> facesOrUnculled(Minecraft mc, String blockId, Direction dir) {
        List<Face> f = faces(mc, blockId, dir);
        return f.isEmpty() ? quads(mc, blockId, null, dir) : f;
    }

    private static BlockStateModel model(Minecraft mc, String blockId) {
        return mc.getModelManager().getBlockStateModelSet().get(block(blockId).defaultBlockState());
    }

    /** The model's quads on one side, in draw order, with their tints. */
    static List<Face> faces(Minecraft mc, String blockId, Direction dir) { return quads(mc, blockId, dir, dir); }

    /** Quads culled by {@code cull} (null = unculled) that face {@code facing}. */
    private static List<Face> quads(Minecraft mc, String blockId, Direction cull, Direction facing) {
        BlockState state = block(blockId).defaultBlockState();
        List<BlockStateModelPart> parts = new ArrayList<>();
        model(mc, blockId).collectParts(RandomSource.create(42), parts);
        List<Face> out = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (BakedQuad q : part.getQuads(cull)) {
                if (q.direction() != facing) continue;
                BakedQuad.MaterialInfo m = q.materialInfo();
                var source = m.isTinted() ? mc.getBlockColors().getTintSource(state, m.tintIndex()) : null; // some tinted quads have no source
                int tint = source != null ? 0xFF000000 | source.color(state) : 0xFFFFFFFF;
                out.add(new Face(m.sprite(), tint));
            }
        }
        return out;
    }

    static void face(GuiGraphicsExtractor g, Face f, int x, int y, int size) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, f.sprite(), x, y, size, size, f.tint());
    }

    static void face(GuiGraphicsExtractor g, List<Face> layers, int x, int y, int size) { for (Face f : layers) face(g, f, x, y, size); }

    /** Greedy word wrap into at most {@code maxLines} lines; the last line is ellipsized if text is left over. */
    static List<String> wrap(Font font, String text, int width, int maxLines) { return wrap(font, text, width, maxLines, false); }

    /** @param quiet shortening is expected (the layout audit doesn't report it) */
    static List<String> wrap(Font font, String text, int width, int maxLines, boolean quiet) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String next = line.isEmpty() ? words[i] : line + " " + words[i];
            if (line.isEmpty() || font.width(next) <= width) { line = new StringBuilder(next); continue; }
            if (lines.size() == maxLines - 1) { // last line: take everything left and cut it
                line.append(" ").append(String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)));
                break;
            }
            lines.add(line.toString());
            line = new StringBuilder(words[i]);
        }
        lines.add(quiet ? ellipsizeQuietly(font, line.toString(), width) : ellipsize(font, line.toString(), width));
        return lines;
    }

    /** Shortens {@code s} with "…" to fit; the layout audit reports it, since cut-off text is usually a layout bug. */
    static String ellipsize(Font font, String s, int maxWidth) {
        if (font.width(s) > maxWidth) LayoutAudit.cut(s);
        return ellipsizeQuietly(font, s, maxWidth);
    }

    /** For text that is expected to be shortened (long saved-palette titles). */
    static String ellipsizeQuietly(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        while (s.length() > 1 && font.width(s + "…") > maxWidth) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    /** Id of the block item in the player's main hand, or null. */
    static String heldBlockId(Minecraft mc) {
        if (mc.player == null) return null;
        if (mc.player.getMainHandItem().getItem() instanceof BlockItem bi) return blockId(BuiltInRegistries.BLOCK.getKey(bi.getBlock()));
        return null;
    }

    /**
     * Creative only: palette blocks go to the hotbar (existing hotbar items move into free inventory slots),
     * their stairs/slabs/walls into the inventory. Uses the creative-inventory packet, so it also works on servers.
     */
    static String giveCreative(Minecraft mc, List<Block> blocks) {
        if (mc.player == null || mc.gameMode == null) return "No player.";
        if (!mc.player.hasInfiniteMaterials()) return "Creative mode only. Use the checklist in survival.";
        Inventory inv = mc.player.getInventory();
        MultiPlayerGameMode gm = mc.gameMode;
        int moved = 0, extras = 0, skipped = 0;
        for (int i = 0; i < blocks.size() && i < 9; i++) {
            ItemStack old = inv.getItem(i);
            if (!old.isEmpty()) {
                int free = freeMainSlot(inv);
                if (free < 0) { skipped++; continue; } // never delete the player's items
                put(inv, gm, free, old.copy());
                moved++;
            }
            put(inv, gm, i, stack(blocks.get(i).id(), 64));
        }
        for (Block b : blocks) {
            for (String v : b.variants()) {
                int free = freeMainSlot(inv);
                if (free < 0) break;
                put(inv, gm, free, stack(v, 64));
                extras++;
            }
        }
        String msg = "Gave " + (Math.min(blocks.size(), 9) - skipped) + " blocks to the hotbar";
        if (extras > 0) msg += ", " + extras + " stairs/slabs/walls to inventory";
        if (moved > 0) msg += " (" + moved + " items moved)";
        if (skipped > 0) msg += ". Inventory full, " + skipped + " skipped";
        return msg + ".";
    }

    private static int freeMainSlot(Inventory inv) {
        for (int j = 9; j < 36; j++) if (inv.getItem(j).isEmpty()) return j;
        return -1;
    }

    /** Inventory slot -> player menu slot: hotbar 0-8 is menu 36-44, main 9-35 is the same index. */
    private static void put(Inventory inv, MultiPlayerGameMode gm, int invSlot, ItemStack s) {
        inv.setItem(invSlot, s);
        gm.handleCreativeModeItemAdd(s, invSlot < 9 ? 36 + invSlot : invSlot);
    }
}
