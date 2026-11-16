package com.p2ppvp.mod.client.skin;

/** Pure client-side pre-check of a skin PNG, mirroring the server rules so errors show before uploading. No Minecraft imports. */
public final class SkinFileCheck {
    public static final int MAX_BYTES = 64 * 1024;
    private static final byte[] SIG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private SkinFileCheck() {}

    /** Returns null when the file looks acceptable, otherwise a short message for the player. */
    public static String check(byte[] data) {
        if (data == null || data.length == 0) return "The file is empty.";
        if (data.length > MAX_BYTES) return "File too big (max " + (MAX_BYTES / 1024) + " KB).";
        if (data.length < 33) return "Not a valid PNG file.";
        for (int i = 0; i < SIG.length; i++) {
            if (data[i] != SIG[i]) return "Not a PNG file.";
        }
        // IHDR chunk starts at byte 8: length(4) type(4) then width(4) height(4) depth(1) colorType(1) compression(1) filter(1) interlace(1)
        if (data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R') return "Not a valid PNG file.";
        int w = readInt(data, 16);
        int h = readInt(data, 20);
        if (w != 64 || h != 64) return "Skin must be exactly 64x64 pixels (this one is " + w + "x" + h + ").";
        int depth = data[24] & 0xFF;
        int colorType = data[25] & 0xFF;
        int interlace = data[28] & 0xFF;
        if (depth != 8 || (colorType != 2 && colorType != 6)) return "Skin must be an 8-bit RGB or RGBA PNG.";
        if (interlace != 0) return "Interlaced PNGs are not supported.";
        return null;
    }

    private static int readInt(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16) | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }
}
