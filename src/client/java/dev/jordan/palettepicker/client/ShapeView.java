package dev.jordan.palettepicker.client;

import dev.jordan.palettepicker.core.BlockData.Face;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A shape in 3D, seen from above at any angle: each block's visible faces (top and the two sides facing the viewer) as
 * sheared quads, drawn back to front. Flat shaded colors when blocks are small, real textures when they're big enough.
 * Shapes too big for 2px a block are drawn at half (or less) resolution so the face count stays low.
 * Layers above {@code upTo} are left off, so stepping through layers builds the shape up.
 */
final class ShapeView {
    static final double MIN_PITCH = Math.toRadians(8), MAX_PITCH = Math.toRadians(80);
    /** Pixels a block below which faces are flat colors, and below which blocks are merged. */
    private static final double TEXTURE_PX = 8, MIN_PX = 2;
    private static final float TOP = 1f, X_SIDE = 0.8f, Z_SIDE = 0.64f;

    double yaw = Math.toRadians(35), pitch = Math.toRadians(30);

    // Cache: the (possibly merged) cells, and the blocks with a visible face in drawing order for one view quadrant.
    private String[][][] source, cells;
    private int lod, upTo = -1, quadrant = -1;
    private int[] order = new int[0];
    private byte[] mask = new byte[0];
    private final Map<String, BlockItems.Face> tops = new HashMap<>();
    private final Map<String, List<BlockItems.Face>> sides = new HashMap<>();

    void turn(double dx, double dy) {
        yaw += dx * 0.012;
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch + dy * 0.01));
    }

    /** Pixels per block for a d-wide, h-tall shape in a w x h box; the same at every angle, so turning it doesn't zoom. */
    private double scale(int d, int layers, int w, int h) {
        double reach = d * Math.sqrt(2);
        return 0.94 * Math.min(w / reach, h / (layers * Math.cos(pitch) + reach * Math.sin(pitch)));
    }

    void draw(GuiGraphicsExtractor g, PickerState st, String[][][] shape, int upTo, int x, int y, int w, int h) {
        int d = shape[0].length, layers = shape.length;
        double k = scale(d, layers, w, h);
        int lod = k >= MIN_PX ? 1 : (int) Math.ceil(MIN_PX / k);
        if (shape != source || lod != this.lod) { source = shape; this.lod = lod; cells = merge(shape, lod); this.upTo = -1; }
        int top = Math.min(cells.length - 1, upTo / lod);
        double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        int quadrant = (sy > 0 ? 1 : 0) | (cy > 0 ? 2 : 0);
        if (top != this.upTo || quadrant != this.quadrant) { this.upTo = top; this.quadrant = quadrant; visible(top, sy > 0, cy > 0); }

        double kk = k * lod; // pixels per (merged) cell
        float exX = (float) (cy * kk), exY = (float) (sy * sp * kk), ezX = (float) (-sy * kk), ezY = (float) (cy * sp * kk), eyY = (float) (-cp * kk);
        // Put the middle of the whole shape (not just the layers shown) in the middle of the box.
        double mx = cells[0][0].length / 2.0, my = cells.length / 2.0, mz = mx;
        float ox = (float) (x + w / 2.0 - (mx * exX + mz * ezX)), oy = (float) (y + h / 2.0 - (mx * exY + my * eyY + mz * ezY));
        boolean textured = kk >= TEXTURE_PX;
        Minecraft mc = Minecraft.getInstance();

        g.enableScissor(x, y, x + w, y + h);
        for (int i = 0; i < order.length; i++) {
            int p = order[i], cx = p & 0xFF, cz = (p >> 8) & 0xFF, cl = p >> 16;
            String id = cells[cl][cz][cx];
            int m = mask[i];
            if ((m & 1) != 0) { // top
                float px = ox + cx * exX + cz * ezX, py = oy + cx * exY + (cl + 1) * eyY + cz * ezY;
                quad(g, mc, st, id, true, TOP, textured, px, py, exX, exY, ezX, ezY);
            }
            if ((m & 2) != 0) { // the x side facing us
                int fx = sy > 0 ? cx + 1 : cx;
                float px = ox + fx * exX + cz * ezX, py = oy + fx * exY + cl * eyY + cz * ezY;
                quad(g, mc, st, id, false, X_SIDE, textured, px, py, ezX, ezY, 0, eyY);
            }
            if ((m & 4) != 0) { // the z side facing us
                int fz = cy > 0 ? cz + 1 : cz;
                float px = ox + cx * exX + fz * ezX, py = oy + cx * exY + cl * eyY + fz * ezY;
                quad(g, mc, st, id, false, Z_SIDE, textured, px, py, exX, exY, 0, eyY);
            }
        }
        g.disableScissor();
    }

    /** One face: the unit square mapped to the parallelogram at (px, py) with edges u and v. */
    private void quad(GuiGraphicsExtractor g, Minecraft mc, PickerState st, String id, boolean top, float shade, boolean textured,
                      float px, float py, float ux, float uy, float vx, float vy) {
        // The GUI culls quads wound the other way (the inventory cube's faces all have a positive determinant). Side faces flip
        // winding as the view turns, so mirror those along u: same parallelogram, drawn.
        if (ux * vy - uy * vx < 0) { px += ux; py += uy; ux = -ux; uy = -uy; }
        g.pose().pushMatrix();
        if (textured) {
            g.pose().mul(new Matrix3x2f(ux / 16, uy / 16, vx / 16, vy / 16, px, py));
            List<BlockItems.Face> layers = top ? List.of(tops.computeIfAbsent(id, i -> BlockItems.topFace(mc, i))) : sides.computeIfAbsent(id, i -> BlockItems.sideFace(mc, i));
            for (BlockItems.Face f : layers) g.blitSprite(RenderPipelines.GUI_TEXTURED, f.sprite(), 0, 0, 16, 16, BlockItems.shade(f.tint(), shade));
        } else {
            g.pose().mul(new Matrix3x2f(ux, uy, vx, vy, px, py));
            int rgb = st.data.get(id).map(b -> b.color(top ? Face.TOP : Face.SIDE)).orElse(0x808080);
            g.fill(0, 0, 1, 1, BlockItems.shade(0xFF000000 | rgb, shade));
        }
        g.pose().popMatrix();
    }

    /** Blocks up to layer {@code top} with a face we can see, far to near; mask bits: 1 top, 2 x side, 4 z side. */
    private void visible(int top, boolean xNear, boolean zNear) {
        int d = cells[0].length;
        int[] o = new int[(top + 1) * d * d];
        byte[] m = new byte[o.length];
        int n = 0;
        for (int l = 0; l <= top; l++) {
            for (int zi = 0; zi < d; zi++) {
                int z = zNear ? zi : d - 1 - zi;
                for (int xi = 0; xi < d; xi++) {
                    int x = xNear ? xi : d - 1 - xi;
                    if (cells[l][z][x] == null) continue;
                    int nx = xNear ? x + 1 : x - 1, nz = zNear ? z + 1 : z - 1, bits = 0;
                    if (l == top || cells[l + 1][z][x] == null) bits |= 1;
                    if (nx < 0 || nx >= d || cells[l][z][nx] == null) bits |= 2;
                    if (nz < 0 || nz >= d || cells[l][nz][x] == null) bits |= 4;
                    if (bits == 0) continue;
                    o[n] = l << 16 | z << 8 | x;
                    m[n++] = (byte) bits;
                }
            }
        }
        order = java.util.Arrays.copyOf(o, n);
        mask = java.util.Arrays.copyOf(m, n);
    }

    /** Every {@code lod}-cube of cells as one cell: its most common block, empty if it's mostly empty. */
    private static String[][][] merge(String[][][] s, int lod) {
        if (lod == 1) return s;
        int layers = s.length, d = s[0].length, ml = (layers + lod - 1) / lod, md = (d + lod - 1) / lod;
        String[][][] out = new String[ml][md][md];
        Map<String, Integer> count = new HashMap<>();
        boolean shell = hollowish(s);
        for (int l = 0; l < ml; l++)
            for (int z = 0; z < md; z++)
                for (int x = 0; x < md; x++) {
                    count.clear();
                    int filled = 0, total = 0;
                    for (int a = l * lod; a < Math.min(layers, (l + 1) * lod); a++)
                        for (int b = z * lod; b < Math.min(d, (z + 1) * lod); b++)
                            for (int c = x * lod; c < Math.min(d, (x + 1) * lod); c++) {
                                total++;
                                if (s[a][b][c] != null) { filled++; count.merge(s[a][b][c], 1, Integer::sum); }
                            }
                    // A thin shell fills little of each cube; keep any cube it passes through so it stays closed.
                    if (filled > 0 && (filled * 4 >= total || shell)) out[l][z][x] = count.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
                }
        return out;
    }

    /** True when the middle of the shape is empty (a shell), where merged cells should keep any block. */
    private static boolean hollowish(String[][][] s) {
        int l = s.length / 2, c = s[0].length / 2;
        return s[l][c][c] == null;
    }
}
