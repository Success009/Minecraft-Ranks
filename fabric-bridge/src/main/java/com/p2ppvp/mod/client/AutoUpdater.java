package com.p2ppvp.mod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2ppvp.mod.DebugLogger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AutoUpdater {
    private static final String REPO = "Success009/Minecraft-Ranks";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static boolean quietlyDisabled = false;

    public static boolean isQuietlyDisabled() {
        return quietlyDisabled;
    }

    public static String getSelfVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("mcr")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("26.1.2-beta.1.37");
        } catch (Throwable t) {
            return "26.1.2-beta.1.37";
        }
    }

    /**
     * Scans the mods/ directory to determine if another instance/version of the mod exists.
     * If this is the lesser version, it sets the quietlyDisabled flag to true.
     * If this is the greater version, it launches a background thread to safely delete the lesser version jar.
     */
    public static void initializeObliterator() {
        try {
            URL clientLocation = AutoUpdater.class.getProtectionDomain().getCodeSource().getLocation();
            File currentJarFile = new File(clientLocation.toURI());

            if (!currentJarFile.isFile() || !currentJarFile.getName().endsWith(".jar")) {
                DebugLogger.log("[OBLITERATOR] Running in non-JAR / development environment. Obliterator bypassed.");
                return;
            }

            File modsDir = currentJarFile.getParentFile();
            if (modsDir == null || !modsDir.isDirectory()) {
                return;
            }

            String selfVersion = getSelfVersion();
            File[] files = modsDir.listFiles((dir, name) -> name.startsWith("mcr-") && name.endsWith(".jar"));

            if (files == null) return;

            File lesserJar = null;
            for (File file : files) {
                if (file.getCanonicalPath().equals(currentJarFile.getCanonicalPath())) {
                    continue;
                }

                String otherVersion = extractVersionFromName(file.getName());
                if (otherVersion == null) continue;

                int comparison = compareVersions(selfVersion, otherVersion);
                if (comparison < 0) {
                    quietlyDisabled = true;
                    DebugLogger.log("[OBLITERATOR] Lesser version active: " + selfVersion + " < " + otherVersion + " (Remote Jar: " + file.getName() + "). Deactivating and disabling ourselves.");
                    break;
                } else if (comparison > 0) {
                    lesserJar = file;
                    DebugLogger.log("[OBLITERATOR] Greater version active: " + selfVersion + " > " + otherVersion + " (Remote Jar: " + file.getName() + "). Will obliterate the lesser version.");
                }
            }

            if (quietlyDisabled) {
                return;
            }

            if (lesserJar != null) {
                final File targetLesserJar = lesserJar;
                Thread deleterThread = new Thread(() -> {
                    try {
                        // Give the lesser version mod ample time to finish class loading and quietly deactivate itself
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {}

                    int retries = 5;
                    while (retries > 0 && targetLesserJar.exists()) {
                        DebugLogger.log("[OBLITERATOR] Attempting to obliterate older version: " + targetLesserJar.getName() + " (Attempt " + (6 - retries) + ")");
                        if (targetLesserJar.delete()) {
                            DebugLogger.log("[OBLITERATOR] Successfully obliterated older version: " + targetLesserJar.getName());
                            break;
                        } else {
                            DebugLogger.log("[OBLITERATOR] Deletion of locked older version failed. Retrying...");
                            try {
                                Thread.sleep(1500);
                            } catch (InterruptedException ignored) {}
                            retries--;
                        }
                    }

                    if (targetLesserJar.exists()) {
                        targetLesserJar.deleteOnExit();
                        DebugLogger.log("[OBLITERATOR] Scheduled deletion of older version on JVM exit: " + targetLesserJar.getName());
                    }
                }, "P2P-Obliterator-Deleter");
                deleterThread.setDaemon(true);
                deleterThread.start();
            }

        } catch (Exception e) {
            DebugLogger.log("[OBLITERATOR] Error during auto-oblit setup: " + e.getMessage());
        }
    }

    public static String extractVersionFromName(String name) {
        if (!name.startsWith("mcr-") || !name.endsWith(".jar")) return null;
        String temp = name.substring(4);
        if (temp.endsWith("-windows.jar")) temp = temp.substring(0, temp.length() - 12);
        else if (temp.endsWith("-linux.jar")) temp = temp.substring(0, temp.length() - 10);
        else if (temp.endsWith("-mac.jar")) temp = temp.substring(0, temp.length() - 8);
        else if (temp.endsWith(".jar")) temp = temp.substring(0, temp.length() - 4);
        return temp;
    }

    public static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("[.\\-]");
        String[] parts2 = v2.split("[.\\-]");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            if (i >= parts1.length) return -1;
            if (i >= parts2.length) return 1;
            String p1 = parts1[i];
            String p2 = parts2[i];
            if (p1.equalsIgnoreCase(p2)) continue;

            boolean isNum1 = p1.matches("\\d+");
            boolean isNum2 = p2.matches("\\d+");
            if (isNum1 && isNum2) {
                int num1 = Integer.parseInt(p1);
                int num2 = Integer.parseInt(p2);
                if (num1 != num2) return Integer.compare(num1, num2);
            } else {
                int comp = p1.compareToIgnoreCase(p2);
                if (comp != 0) return comp;
            }
        }
        return 0;
    }

    public static void checkForUpdatesAsync() {
        if (quietlyDisabled) {
            DebugLogger.log("[AUTO-UPDATER] Mod is quietly disabled as lesser version. Skipping update check.");
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                URL clientLocation = AutoUpdater.class.getProtectionDomain().getCodeSource().getLocation();
                File currentJarFile = new File(clientLocation.toURI());

                if (!currentJarFile.isFile() || !currentJarFile.getName().endsWith(".jar")) {
                    DebugLogger.log("[AUTO-UPDATER] Running in development environment. Skipping update check.");
                    return;
                }

                String currentJarName = currentJarFile.getName();
                DebugLogger.log("[AUTO-UPDATER] Checking for updates. Current active JAR: " + currentJarName);

                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "MCR-AutoUpdater")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
                    
                    if (!release.has("tag_name")) {
                        DebugLogger.log("[AUTO-UPDATER] Release has no tag_name.");
                        return;
                    }

                    String tagName = release.get("tag_name").getAsString();
                    if (tagName.startsWith("v")) {
                        tagName = tagName.substring(1);
                    }

                    String selfVersion = getSelfVersion();
                    DebugLogger.log("[AUTO-UPDATER] Remote version from tag: " + tagName + " | Self version: " + selfVersion);

                    if (compareVersions(tagName, selfVersion) > 0) {
                        DebugLogger.log("[AUTO-UPDATER] Newer version detected on GitHub (" + tagName + "). Preparing to download...");
                        
                        String os = System.getProperty("os.name").toLowerCase();
                        String platformSuffix;
                        if (os.contains("win")) {
                            platformSuffix = "-windows.jar";
                        } else if (os.contains("mac") || os.contains("darwin")) {
                            platformSuffix = "-mac.jar";
                        } else {
                            platformSuffix = "-linux.jar";
                        }

                        if (release.has("assets")) {
                            JsonArray assets = release.getAsJsonArray("assets");
                            String downloadUrl = null;
                            String assetName = null;

                            for (JsonElement assetEl : assets) {
                                JsonObject asset = assetEl.getAsJsonObject();
                                if (asset.has("name") && asset.has("browser_download_url")) {
                                    String name = asset.get("name").getAsString();
                                    if (name.endsWith(platformSuffix)) {
                                        assetName = name;
                                        downloadUrl = asset.get("browser_download_url").getAsString();
                                        break;
                                    }
                                }
                            }

                            if (downloadUrl != null && assetName != null) {
                                DebugLogger.log("[AUTO-UPDATER] Downloading and applying update: " + assetName);
                                applyUpdate(currentJarFile, downloadUrl, assetName);
                            } else {
                                DebugLogger.log("[AUTO-UPDATER] No asset found for suffix: " + platformSuffix);
                            }
                        }
                    } else {
                        DebugLogger.log("[AUTO-UPDATER] Mod is up to date (current version matches latest GitHub tag).");
                    }
                } else {
                    DebugLogger.log("[AUTO-UPDATER] GitHub API request failed with status: " + response.statusCode());
                }
            } catch (Exception e) {
                DebugLogger.log("[AUTO-UPDATER] Error checking for updates: " + e.getMessage());
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static void applyUpdate(File currentJarFile, String downloadUrl, String assetName) {
        try {
            File modsDirectory = currentJarFile.getParentFile();
            File targetNewJar = new File(modsDirectory, assetName);

            DebugLogger.log("[AUTO-UPDATER] Downloading update to: " + targetNewJar.getAbsolutePath());
            downloadFile(downloadUrl, targetNewJar);
            DebugLogger.log("[AUTO-UPDATER] Silently downloaded new update: " + assetName);

            currentJarFile.deleteOnExit();
            DebugLogger.log("[AUTO-UPDATER] Scheduled deletion of current version on exit: " + currentJarFile.getName());
        } catch (Exception e) {
            DebugLogger.log("[AUTO-UPDATER] Error applying update: " + e.getMessage());
        }
    }

    private static void downloadFile(String fileUrl, File targetFile) throws Exception {
        URI uri = URI.create(fileUrl);
        URL url = uri.toURL();
        try (InputStream in = new BufferedInputStream(url.openStream());
             FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }
}
