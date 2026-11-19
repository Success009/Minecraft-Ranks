package com.p2ppvp.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JanitorPreLaunchTest {

    @TempDir
    Path tmp;

    private File makeJar(String name, String modJson) throws Exception {
        File f = tmp.resolve(name).toFile();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            if (modJson != null) {
                z.putNextEntry(new ZipEntry("fabric.mod.json"));
                z.write(modJson.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            z.putNextEntry(new ZipEntry("dummy.txt"));
            z.write(1);
            z.closeEntry();
        }
        return f;
    }

    @Test
    void newerBuildNumberWins() {
        assertTrue(JanitorPreLaunch.compareVersions("26.1.2-beta.1.61", "26.1.2-beta.1.60") > 0);
        assertTrue(JanitorPreLaunch.compareVersions("26.1.2-beta.1.60", "26.1.2-beta.1.61") < 0);
        assertEquals(0, JanitorPreLaunch.compareVersions("26.1.2-beta.1.60", "26.1.2-beta.1.60"));
    }

    @Test
    void numericPartsCompareAsNumbersNotText() {
        assertTrue(JanitorPreLaunch.compareVersions("26.1.2-beta.1.100", "26.1.2-beta.1.99") > 0);
        assertTrue(JanitorPreLaunch.compareVersions("26.1.2-beta.1.9", "26.1.2-beta.1.10") < 0);
    }

    @Test
    void readsVersionFromValidMcrJar() throws Exception {
        File jar = makeJar("MCR-x-linux.jar", "{\"id\":\"mcr\",\"version\":\"26.1.2-beta.1.61\"}");
        assertEquals("26.1.2-beta.1.61", JanitorPreLaunch.readJarVersion(jar));
    }

    @Test
    void rejectsForeignModId() throws Exception {
        File jar = makeJar("MCR-x-linux.jar", "{\"id\":\"other\",\"version\":\"1.0\"}");
        assertNull(JanitorPreLaunch.readJarVersion(jar));
    }

    @Test
    void rejectsJarWithoutModJson() throws Exception {
        File jar = makeJar("MCR-x-linux.jar", null);
        assertNull(JanitorPreLaunch.readJarVersion(jar));
    }

    @Test
    void rejectsUnexpandedVersionPlaceholder() throws Exception {
        File jar = makeJar("MCR-x-linux.jar", "{\"id\":\"mcr\",\"version\":\"${version}\"}");
        assertNull(JanitorPreLaunch.readJarVersion(jar));
    }

    @Test
    void rejectsCorruptOrTruncatedFile() throws Exception {
        File f = tmp.resolve("MCR-bad-linux.jar").toFile();
        Files.write(f.toPath(), "this is not a zip".getBytes(StandardCharsets.UTF_8));
        assertNull(JanitorPreLaunch.readJarVersion(f));

        File good = makeJar("MCR-good-linux.jar", "{\"id\":\"mcr\",\"version\":\"1.2.3\"}");
        byte[] bytes = Files.readAllBytes(good.toPath());
        File cut = tmp.resolve("MCR-cut-linux.jar").toFile();
        Files.write(cut.toPath(), java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertNull(JanitorPreLaunch.readJarVersion(cut));
    }

    @Test
    void missingFileIsNull() {
        assertNull(JanitorPreLaunch.readJarVersion(tmp.resolve("nope.jar").toFile()));
    }
}
