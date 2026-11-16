package com.p2ppvp.mod.client.skin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SkinFileCheckTest {
    private static byte[] png(int w, int h, int depth, int colorType, int interlace, int totalLen) {
        byte[] d = new byte[Math.max(totalLen, 33)];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, d, 0, 8);
        d[11] = 13; // IHDR length
        d[12] = 'I'; d[13] = 'H'; d[14] = 'D'; d[15] = 'R';
        putInt(d, 16, w);
        putInt(d, 20, h);
        d[24] = (byte) depth;
        d[25] = (byte) colorType;
        d[28] = (byte) interlace;
        return d;
    }

    private static void putInt(byte[] d, int off, int v) {
        d[off] = (byte) (v >>> 24);
        d[off + 1] = (byte) (v >>> 16);
        d[off + 2] = (byte) (v >>> 8);
        d[off + 3] = (byte) v;
    }

    @Test
    void acceptsRgba64() {
        assertNull(SkinFileCheck.check(png(64, 64, 8, 6, 0, 200)));
    }

    @Test
    void acceptsRgb64() {
        assertNull(SkinFileCheck.check(png(64, 64, 8, 2, 0, 200)));
    }

    @Test
    void rejectsWrongSize() {
        String msg = SkinFileCheck.check(png(64, 32, 8, 6, 0, 200));
        assertNotNull(msg);
        assertTrue(msg.contains("64x64"));
        assertTrue(msg.contains("64x32"));
        assertNotNull(SkinFileCheck.check(png(128, 128, 8, 6, 0, 200)));
    }

    @Test
    void rejectsTooBig() {
        String msg = SkinFileCheck.check(png(64, 64, 8, 6, 0, SkinFileCheck.MAX_BYTES + 1));
        assertNotNull(msg);
        assertTrue(msg.contains("too big"));
        assertNull(SkinFileCheck.check(png(64, 64, 8, 6, 0, SkinFileCheck.MAX_BYTES)));
    }

    @Test
    void rejectsBadSignature() {
        byte[] d = png(64, 64, 8, 6, 0, 200);
        d[1] = 'X';
        assertEquals("Not a PNG file.", SkinFileCheck.check(d));
    }

    @Test
    void rejectsMissingIhdr() {
        byte[] d = png(64, 64, 8, 6, 0, 200);
        d[12] = 'X';
        assertEquals("Not a valid PNG file.", SkinFileCheck.check(d));
    }

    @Test
    void rejectsEmptyNullAndTruncated() {
        assertNotNull(SkinFileCheck.check(null));
        assertNotNull(SkinFileCheck.check(new byte[0]));
        assertEquals("Not a valid PNG file.", SkinFileCheck.check(new byte[20]));
    }

    @Test
    void rejectsWrongDepthColorTypeAndInterlace() {
        assertNotNull(SkinFileCheck.check(png(64, 64, 16, 6, 0, 200)));
        assertNotNull(SkinFileCheck.check(png(64, 64, 8, 3, 0, 200)));
        assertNotNull(SkinFileCheck.check(png(64, 64, 8, 0, 0, 200)));
        assertEquals("Interlaced PNGs are not supported.", SkinFileCheck.check(png(64, 64, 8, 6, 1, 200)));
    }
}
