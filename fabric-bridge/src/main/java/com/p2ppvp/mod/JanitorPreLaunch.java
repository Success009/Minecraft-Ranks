package com.p2ppvp.mod;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Runs before the game loads any mod code. Handles side-by-side copies of MCR in the mods folder:
 *  - the lesser version sets {@link #isDisabled()} and stays inert,
 *  - the greater version deletes the lesser jar (or renames it to .bak if it is locked),
 *  - corrupt jars and leftover .part downloads are removed.
 * Versions are read from each jar's fabric.mod.json, never from file names.
 */
public class JanitorPreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcr-janitor");
    private static final String MOD_ID = "mcr";

    private static volatile boolean disabled = false;

    public static boolean isDisabled() {
        return disabled;
    }

    static {
        try {
            disabled = checkAndCleanDuplicates();
        } catch (Throwable t) {
            // Never crash the boot sequence.
        }
    }

    @Override
    public void onPreLaunch() {
        if (disabled) {
            LOGGER.info("[MCR-JANITOR] A newer MCR jar is present. This jar stays disabled.");
        }
    }

    private static boolean isMcrName(String name, String suffix) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("mcr-") && n.endsWith(suffix);
    }

    private static boolean checkAndCleanDuplicates() {
        try {
            URL location = JanitorPreLaunch.class.getProtectionDomain().getCodeSource().getLocation();
            File ourJar = new File(location.toURI());
            if (!ourJar.isFile() || !ourJar.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return false; // development environment
            }
            File modsDir = ourJar.getParentFile();
            if (modsDir == null || !modsDir.isDirectory()) return false;

            // Leftovers from aborted downloads and earlier swaps.
            File[] junk = modsDir.listFiles((d, n) -> isMcrName(n, ".part") || isMcrName(n, ".jar.bak") || isMcrName(n, ".jar.corrupt"));
            if (junk != null) {
                for (File f : junk) {
                    try {
                        if (f.delete()) LOGGER.info("[MCR-JANITOR] Removed leftover file: " + f.getName());
                    } catch (Throwable ignored) {}
                }
            }

            String ourVersion = readJarVersion(ourJar);
            if (ourVersion == null) return false; // cannot judge ourselves; do not disable

            File[] jars = modsDir.listFiles((d, n) -> isMcrName(n, ".jar"));
            if (jars == null || jars.length <= 1) return false;

            String ourCanonical = ourJar.getCanonicalPath();
            boolean weAreLesser = false;

            for (File other : jars) {
                if (other.getCanonicalPath().equals(ourCanonical)) continue;

                String otherVersion = readJarVersion(other);
                if (otherVersion == null) {
                    LOGGER.warn("[MCR-JANITOR] Corrupt or foreign jar: " + other.getName() + " -> removing");
                    removeJar(other, ".corrupt");
                    continue;
                }

                int cmp = compareVersions(ourVersion, otherVersion);
                if (cmp == 0) {
                    cmp = ourJar.getName().compareTo(other.getName()) > 0 ? -1 : 1; // deterministic tie-break
                }
                if (cmp < 0) {
                    weAreLesser = true;
                } else {
                    LOGGER.info("[MCR-JANITOR] Removing older jar: " + other.getName() + " (" + otherVersion + " < " + ourVersion + ")");
                    removeJar(other, ".bak");
                }
            }
            return weAreLesser;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void removeJar(File jar, String fallbackSuffix) {
        try {
            if (jar.delete()) return;
            File renamed = new File(jar.getParentFile(), jar.getName() + fallbackSuffix);
            if (jar.renameTo(renamed)) return;
            jar.deleteOnExit();
        } catch (Throwable t) {
            jar.deleteOnExit();
        }
    }

    /** Returns the mod version from fabric.mod.json, or null if the jar is unreadable, not MCR, or has no version. */
    public static String readJarVersion(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("fabric.mod.json");
            if (entry == null) return null;
            try (InputStream is = jar.getInputStream(entry)) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(content).getAsJsonObject();
                if (!json.has("id") || !MOD_ID.equals(json.get("id").getAsString())) return null;
                if (!json.has("version")) return null;
                String v = json.get("version").getAsString();
                return (v == null || v.isBlank() || v.contains("${")) ? null : v;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static int compareVersions(String v1, String v2) {
        String[] a = v1.split("[.\\-]");
        String[] b = v2.split("[.\\-]");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            if (i >= a.length) return -1;
            if (i >= b.length) return 1;
            String p1 = a[i], p2 = b[i];
            if (p1.equalsIgnoreCase(p2)) continue;
            boolean n1 = p1.matches("\\d+"), n2 = p2.matches("\\d+");
            if (n1 && n2) {
                int c = Long.compare(Long.parseLong(p1), Long.parseLong(p2));
                if (c != 0) return c;
            } else {
                int c = p1.compareToIgnoreCase(p2);
                if (c != 0) return c;
            }
        }
        return 0;
    }
}
