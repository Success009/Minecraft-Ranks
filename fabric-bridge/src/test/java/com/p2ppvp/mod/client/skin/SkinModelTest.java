package com.p2ppvp.mod.client.skin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SkinModelTest {
    private static int[] blank() {
        return new int[64 * 64];
    }

    @Test
    void transparentColumnsMeanSlim() {
        assertTrue(SkinModel.isSlim(blank(), 64, 64));
    }

    @Test
    void paintedColumnsMeanClassic() {
        int[] px = blank();
        for (int y = 20; y <= 31; y++) {
            px[y * 64 + 54] = 0xFF112233;
            px[y * 64 + 55] = 0xFF112233;
        }
        assertFalse(SkinModel.isSlim(px, 64, 64));
    }

    @Test
    void singlePaintedPixelMeansClassic() {
        int[] px = blank();
        px[25 * 64 + 55] = 0x01000000;
        assertFalse(SkinModel.isSlim(px, 64, 64));
    }

    @Test
    void pixelsOutsideTheRegionAreIgnored() {
        int[] px = blank();
        px[19 * 64 + 54] = 0xFFFFFFFF;
        px[32 * 64 + 55] = 0xFFFFFFFF;
        px[25 * 64 + 53] = 0xFFFFFFFF;
        px[25 * 64 + 56] = 0xFFFFFFFF;
        assertTrue(SkinModel.isSlim(px, 64, 64));
    }

    @Test
    void badInputIsNotSlim() {
        assertFalse(SkinModel.isSlim(null, 64, 64));
        assertFalse(SkinModel.isSlim(blank(), 64, 32));
        assertFalse(SkinModel.isSlim(new int[10], 64, 64));
    }
}
