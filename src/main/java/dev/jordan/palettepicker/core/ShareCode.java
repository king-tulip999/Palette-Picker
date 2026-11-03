package dev.jordan.palettepicker.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Short text codes for sharing a palette: {@code pp1:} + base64url of the deflated {@code key=value} lines, compressed against a
 * dictionary of common words so a typical code fits in one chat message (256 characters). Pure Java: no Minecraft classes.
 */
public final class ShareCode {
    private ShareCode() {}

    public static final String PREFIX = "pp1:";
    /** Part of the format: changing it breaks every pp1 code, so a new dictionary needs a new prefix. */
    private static final byte[] DICT = ("mode=building\nmode=path\nmode=floor\nmode=shape\nbase=\ntheme=\nsize=\ntone=\nwidth=\nsides=\nweights=\n"
            + "pattern=\nfshape=\nfsize=\nring=\nfshift=\nfseed=\nkind=\nssize=\nheight=\nhollow=\nthick=\nfill=\nsshift=\nsseed=\n"
            + "walls=\nroof=\nfloor=\nshade=\ntrim=\naccent=\nmain=\nsecond=\nmix=\ndetail=\nedge=\npatch=\nstones=\nborder=\n"
            + "true\nfalse\n_planks\n_log\n_bricks\n_stone\nstone_bricks\ncobblestone\ndeepslate\npolished_\nstripped_\nsmooth_\nchiseled_\n"
            + "_terracotta\n_concrete\n_wool\nspruce\noak\nbirch\ndark_oak\njungle\nacacia\nmangrove\ncherry\npale_oak\nbamboo\ncrimson\nwarped\n"
            + "andesite\ndiorite\ngranite\ncalcite\ntuff\nblackstone\nsandstone\nquartz\ncopper\nmud\nmoss\ngravel\nsand\ndirt\n").getBytes(StandardCharsets.UTF_8);

    public static String encode(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setDictionary(DICT); // must match decode's
        d.setInput(sb.toString().getBytes(StandardCharsets.UTF_8));
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** @throws IllegalArgumentException if it isn't a code (wrong prefix, cut short, garbage) */
    public static Map<String, String> decode(String code) {
        String c = code.strip();
        if (!c.startsWith(PREFIX)) throw new IllegalArgumentException("not a palette code");
        Inflater inf = new Inflater(true);
        try {
            inf.setDictionary(DICT); // raw deflate: no header asks for it, so set it up front
            inf.setInput(Base64.getUrlDecoder().decode(c.substring(PREFIX.length())));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && inf.needsInput()) throw new IllegalArgumentException("palette code is cut short");
                if (out.size() > 8192) throw new IllegalArgumentException("not a palette code");
                out.write(buf, 0, n);
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) fields.put(line.substring(0, eq), line.substring(eq + 1));
            }
            return fields;
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("broken palette code", e);
        } finally {
            inf.end();
        }
    }
}
