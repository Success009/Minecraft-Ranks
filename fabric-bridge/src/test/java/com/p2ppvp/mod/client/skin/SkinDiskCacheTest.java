package com.p2ppvp.mod.client.skin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkinDiskCacheTest {
    @TempDir
    Path dir;

    @Test
    void saveThenLoadRoundTrips() {
        SkinDiskCache c = new SkinDiskCache(dir);
        byte[] png = {1, 2, 3, 4};
        assertTrue(c.save("full", "Steve", "t1", png));
        assertArrayEquals(png, c.load("full", "steve", "t1"));
    }

    @Test
    void loadMissesOnOtherTokenOrKind() {
        SkinDiskCache c = new SkinDiskCache(dir);
        c.save("head", "steve", "t1", new byte[] {9});
        assertNull(c.load("head", "steve", "t2"));
        assertNull(c.load("full", "steve", "t1"));
        assertNull(c.load("head", "alex", "t1"));
    }

    @Test
    void newTokenReplacesOldFilesOfSamePlayerOnly() throws Exception {
        SkinDiskCache c = new SkinDiskCache(dir);
        c.save("full", "steve", "t1", new byte[] {1});
        c.save("full", "alex", "t1", new byte[] {7});
        c.save("full", "steve", "t2", new byte[] {2});
        assertNull(c.load("full", "steve", "t1"));
        assertArrayEquals(new byte[] {2}, c.load("full", "steve", "t2"));
        assertArrayEquals(new byte[] {7}, c.load("full", "alex", "t1"));
        try (var s = Files.list(dir.resolve("full"))) {
            assertEquals(2, s.count());
        }
    }

    @Test
    void rejectsUnsafeNamesTokensKindsAndSizes() {
        SkinDiskCache c = new SkinDiskCache(dir);
        assertFalse(c.save("full", "../evil", "t1", new byte[] {1}));
        assertFalse(c.save("full", "steve", "../x", new byte[] {1}));
        assertFalse(c.save("other", "steve", "t1", new byte[] {1}));
        assertFalse(c.save("full", "steve", "t1", new byte[0]));
        assertFalse(c.save("full", "steve", "t1", null));
        assertFalse(c.save("full", "steve", "t1", new byte[300 * 1024]));
        assertNull(c.load("full", "../evil", "t1"));
        assertNull(c.load("full", "steve", "a/b"));
    }

    @Test
    void deleteRemovesAllKindsForPlayer() {
        SkinDiskCache c = new SkinDiskCache(dir);
        c.save("head", "steve", "t1", new byte[] {1});
        c.save("full", "steve", "t1", new byte[] {2});
        c.save("full", "alex", "t1", new byte[] {3});
        c.delete("Steve");
        assertNull(c.load("head", "steve", "t1"));
        assertNull(c.load("full", "steve", "t1"));
        assertNotNull(c.load("full", "alex", "t1"));
    }

    @Test
    void missingRootIsFine() {
        SkinDiskCache c = new SkinDiskCache(dir.resolve("nope"));
        assertNull(c.load("full", "steve", "t1"));
        c.delete("steve");
    }
}
