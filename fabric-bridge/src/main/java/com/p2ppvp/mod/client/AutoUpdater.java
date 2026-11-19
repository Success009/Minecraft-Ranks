package com.p2ppvp.mod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2ppvp.mod.DebugLogger;
import com.p2ppvp.mod.JanitorPreLaunch;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;

public class AutoUpdater {
    private static final String REPO = "Success009/Minecraft-Ranks";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final int MAX_BYTES_PER_SECOND = 500 * 1024;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private static volatile boolean updateCompleted = false;
    private static volatile boolean checkerStarted = false;

    /** True when a newer MCR jar sits next to this one. Decided at class load by the pre-launch janitor. */
    public static boolean isQuietlyDisabled() {
        return JanitorPreLaunch.isDisabled();
    }

    public static boolean isUpdateCompleted() {
        return updateCompleted;
    }

    public static String getSelfVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("mcr")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("0");
        } catch (Throwable t) {
            return "0";
        }
    }

    /** Kept so existing callers compile. The janitor now does this work before the game loads. */
    public static void initializeObliterator() {
    }

    public static int compareVersions(String v1, String v2) {
        return JanitorPreLaunch.compareVersions(v1, v2);
    }

    public static void checkForUpdatesAsync() {
        if (isQuietlyDisabled()) {
            DebugLogger.log("[AUTO-UPDATER] Lesser version, staying disabled. Skipping update check.");
            return;
        }
        if (checkerStarted) return;
        checkerStarted = true;

        Thread thread = new Thread(() -> {
            while (!updateCompleted) {
                try {
                    File currentJar = currentJarFile();
                    if (currentJar == null) {
                        DebugLogger.log("[AUTO-UPDATER] Development build. Skipping update check.");
                        return;
                    }
                    if (new File(currentJar.getParentFile(), "noupdate.txt").exists()) {
                        DebugLogger.log("[AUTO-UPDATER] noupdate.txt present. Skipping update check.");
                        return;
                    }
                    checkOnce(currentJar);
                } catch (Throwable t) {
                    DebugLogger.log("[AUTO-UPDATER] Error checking for updates: " + t.getMessage());
                }
                if (updateCompleted) break;
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "MCR-AutoUpdater");
        thread.setDaemon(true);
        thread.start();
    }

    private static File currentJarFile() {
        try {
            URL loc = AutoUpdater.class.getProtectionDomain().getCodeSource().getLocation();
            File f = new File(loc.toURI());
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) return f;
        } catch (Throwable ignored) {}
        return null;
    }

    private static void checkOnce(File currentJar) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "MCR-AutoUpdater")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            DebugLogger.log("[AUTO-UPDATER] GitHub API status " + response.statusCode());
            return;
        }

        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!release.has("tag_name") || !release.has("assets")) return;

        String tag = release.get("tag_name").getAsString();
        if (tag.startsWith("v")) tag = tag.substring(1);

        String self = readOwnVersion(currentJar);
        if (self == null || self.equals("0")) {
            DebugLogger.log("[AUTO-UPDATER] Cannot determine own version. Skipping update to stay safe.");
            return;
        }
        if (compareVersions(tag, self) <= 0) {
            DebugLogger.log("[AUTO-UPDATER] Up to date (" + self + ", latest " + tag + ").");
            return;
        }

        String suffix = platformSuffix();
        String url = null, name = null;
        JsonArray assets = release.getAsJsonArray("assets");
        for (JsonElement el : assets) {
            JsonObject a = el.getAsJsonObject();
            if (!a.has("name") || !a.has("browser_download_url")) continue;
            String n = a.get("name").getAsString();
            if (n.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                name = n;
                url = a.get("browser_download_url").getAsString();
                break;
            }
        }
        if (url == null) {
            DebugLogger.log("[AUTO-UPDATER] No asset ending in " + suffix);
            return;
        }

        DebugLogger.log("[AUTO-UPDATER] New version " + tag + " found. Downloading " + name);
        applyUpdate(currentJar, url, name, tag, self);
    }

    private static String readOwnVersion(File currentJar) {
        String v = JanitorPreLaunch.readJarVersion(currentJar);
        return v != null ? v : getSelfVersion();
    }

    private static String platformSuffix() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "-windows.jar";
        if (os.contains("mac") || os.contains("darwin")) return "-mac.jar";
        return "-linux.jar";
    }

    private static void applyUpdate(File currentJar, String url, String assetName, String tag, String selfVersion) {
        File modsDir = currentJar.getParentFile();
        File finalJar = new File(modsDir, assetName);
        // Not a mod extension, so Fabric can never load a half-written download.
        File partFile = new File(modsDir, assetName + ".part");

        try {
            downloadThrottled(url, partFile);

            String downloadedVersion = JanitorPreLaunch.readJarVersion(partFile);
            if (downloadedVersion == null) {
                throw new java.io.IOException("Downloaded file is not a valid MCR jar (corrupt, incomplete or wrong mod).");
            }
            if (compareVersions(downloadedVersion, tag) != 0) {
                throw new java.io.IOException("Downloaded version " + downloadedVersion + " does not match release " + tag + ".");
            }
            if (compareVersions(downloadedVersion, selfVersion) <= 0) {
                throw new java.io.IOException("Downloaded version " + downloadedVersion + " is not newer than " + selfVersion + ".");
            }

            // Verified. Only now does it become a real jar.
            try {
                Files.move(partFile.toPath(), finalJar.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(partFile.toPath(), finalJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            updateCompleted = true;
            DebugLogger.log("[AUTO-UPDATER] Update installed: " + assetName + ". It takes effect on the next launch.");

            retireCurrentJar(currentJar, finalJar);
        } catch (Throwable t) {
            DebugLogger.log("[AUTO-UPDATER] Update failed: " + t.getMessage());
            try { partFile.delete(); } catch (Throwable ignored) {}
        }
    }

    /** Removes the running jar so Fabric never sees two copies next boot. Falls back to .bak when locked. */
    private static void retireCurrentJar(File currentJar, File newJar) {
        try {
            if (currentJar.getCanonicalPath().equals(newJar.getCanonicalPath())) return; // same name, already replaced
            if (currentJar.delete()) {
                DebugLogger.log("[AUTO-UPDATER] Removed old jar: " + currentJar.getName());
                return;
            }
            File bak = new File(currentJar.getParentFile(), currentJar.getName() + ".bak");
            if (currentJar.renameTo(bak)) {
                DebugLogger.log("[AUTO-UPDATER] Old jar locked. Renamed to " + bak.getName());
            } else {
                currentJar.deleteOnExit();
                DebugLogger.log("[AUTO-UPDATER] Old jar locked. Will be removed on exit; the janitor also removes it next launch.");
            }
        } catch (Throwable t) {
            currentJar.deleteOnExit();
        }
    }

    private static void downloadThrottled(String fileUrl, File target) throws Exception {
        URL url = URI.create(fileUrl).toURL();
        try (InputStream in = new BufferedInputStream(url.openStream());
             FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buf = new byte[8192];
            int n;
            long start = System.currentTimeMillis();
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                long elapsed = System.currentTimeMillis() - start;
                long expectedMs = (long) (total * 1000.0 / MAX_BYTES_PER_SECOND);
                if (expectedMs - elapsed > 10) Thread.sleep(expectedMs - elapsed);
            }
            out.flush();
            out.getFD().sync();
        } catch (Throwable t) {
            try { target.delete(); } catch (Throwable ignored) {}
            throw t;
        }
    }
}
