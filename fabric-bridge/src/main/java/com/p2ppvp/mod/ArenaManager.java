package com.p2ppvp.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

public class ArenaManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("p2ppvp-arena");
    private static final String ARENA_CACHE_NAME = "p2p_arena_cache";

    private static volatile boolean extracting = false;
    private static final Object lockObject = new Object();

    public static boolean isExtracting() {
        return extracting;
    }

    public static void waitForExtraction() {
        if (!extracting) return;
        synchronized (lockObject) {
            while (extracting) {
                try {
                    lockObject.wait(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    /**
     * Initializes the arena cache asynchronously. Unpacks the pristine helios.tar.gz template
     * into .minecraft/saves/p2p_arena_cache on startup to guarantee a clean, uncorrupted copy.
     */
    public static void initializeArenaCacheAsync() {
        Thread thread = new Thread(() -> {
            initializeArenaCacheSync();
        }, "P2PArenaBootstrap");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Initializes the arena cache synchronously. Includes a retry mechanism to handle
     * potential file locks if the previous singleplayer integrated server is still shutting down.
     */
    public static boolean initializeArenaCacheSync() {
        synchronized (lockObject) {
            extracting = true;
        }
        try {
            Path savesDir = Paths.get("saves");
            Path targetArena = savesDir.resolve(ARENA_CACHE_NAME);

            LOGGER.info("P2P Arena cache: Deleting existing cache to prevent world corruption/desync...");

            // Retry deletion up to 15 times with 150ms intervals if locks are still held
            boolean deleted = false;
            for (int i = 0; i < 15; i++) {
                deleteDirectoryRecursively(targetArena);
                if (!Files.exists(targetArena)) {
                    deleted = true;
                    break;
                }
                LOGGER.info("World files still locked. Waiting for server shutdown... (Attempt " + (i + 1) + ")");
                Thread.sleep(150);
            }

            if (!deleted) {
                LOGGER.warn("P2P Arena cache: Could not fully delete existing cache. Retrying with one final force deletion...");
                deleteDirectoryRecursively(targetArena);
            }

            LOGGER.info("Preparing crash-proof extraction of pristine arena...");
            Files.createDirectories(targetArena);

            try (InputStream is = ArenaManager.class.getResourceAsStream("/assets/p2ppvp/arena/helios.tar.gz")) {
                if (is == null) {
                    LOGGER.error("Resource helios.tar.gz not found on classpath!");
                    return false;
                }
                extractTarGz(is, targetArena);
                LOGGER.info("Successfully extracted pristine helios void arena map to: " + targetArena.toAbsolutePath());
            } catch (Exception e) {
                LOGGER.error("Error occurred during helios.tar.gz extraction: " + e.getMessage(), e);
                deleteDirectoryRecursively(targetArena);
                return false;
            }

            // Copy level.dat and level.dat_old templates to make the map recognizable by Minecraft Singleplayer
            try (InputStream lvlIn = ArenaManager.class.getResourceAsStream("/assets/p2ppvp/arena/level.dat")) {
                if (lvlIn != null) {
                    Files.copy(lvlIn, targetArena.resolve("level.dat"), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            try (InputStream lvlOldIn = ArenaManager.class.getResourceAsStream("/assets/p2ppvp/arena/level.dat_old")) {
                if (lvlOldIn != null) {
                    Files.copy(lvlOldIn, targetArena.resolve("level.dat_old"), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            LOGGER.info("Successfully extracted and initialized singleplayer level.dat metadata.");
            return true;

        } catch (Exception e) {
            LOGGER.error("General failure in arena cache initialization bootstrap: " + e.getMessage(), e);
            return false;
        } finally {
            synchronized (lockObject) {
                extracting = false;
                lockObject.notifyAll();
            }
        }
    }

    /**
     * Minimal, zero-dependency pure-Java GZIP & Tar format extractor.
     * Prevents third-party library dependency crashes.
     */
    private static void extractTarGz(InputStream rawIn, Path targetDir) throws IOException {
        try (GZIPInputStream gzipIn = new GZIPInputStream(rawIn)) {
            byte[ ] header = new byte[512];
            byte[ ] buffer = new byte[4096];

            while (true) {
                // Read 512-byte tar block header
                int bytesRead = readFully(gzipIn, header, 0, 512);
                if (bytesRead < 512) {
                    break; // End of archive
                }

                // Check for null block (end of archive indicator)
                boolean allNull = true;
                for (byte b : header) {
                    if (b != 0) {
                        allNull = false;
                        break;
                    }
                }
                if (allNull) {
                    break;
                }

                // Extract filename from header (null-terminated ASCII)
                String name = new String(header, 0, 100, StandardCharsets.US_ASCII).trim();
                if (name.isEmpty()) {
                    continue;
                }

                // Strip the "helios/" prefix if present to extract files directly into saves/p2p_arena_cache
                if (name.startsWith("helios/")) {
                    name = name.substring("helios/".length());
                } else if (name.equals("helios")) {
                    continue; // Skip the directory entry itself
                }

                if (name.isEmpty()) {
                    continue;
                }

                // Extract file size from header (12-byte octal value)
                String sizeStr = new String(header, 124, 12, StandardCharsets.US_ASCII).trim();
                long size = 0;
                try {
                    size = Long.parseLong(sizeStr, 8);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid size octal in tar header: " + sizeStr);
                }

                // Type flag (Offset 156: '0' or 0 is regular file, '5' is directory)
                byte typeFlag = header[156];
                Path targetFile = targetDir.resolve(name);

                if (typeFlag == '5' || name.endsWith("/")) {
                    Files.createDirectories(targetFile);
                } else {
                    // Ensure parent directories exist
                    Path parent = targetFile.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }

                    // Write file contents out
                    try (OutputStream out = Files.newOutputStream(targetFile)) {
                        long remaining = size;
                        while (remaining > 0) {
                            int toRead = (int) Math.min(buffer.length, remaining);
                            int read = gzipIn.read(buffer, 0, toRead);
                            if (read == -1) {
                                throw new EOFException("Unexpected EOF while reading tar file entry content: " + name);
                            }
                            out.write(buffer, 0, read);
                            remaining -= read;
                        }
                    }
                }

                // Tar entries are aligned to 512-byte blocks. Skip any trailing padding.
                long padding = (512 - (size % 512)) % 512;
                long skipped = 0;
                while (skipped < padding) {
                    long skipRead = gzipIn.skip(padding - skipped);
                    if (skipRead <= 0) {
                        // If skip is not supported or returns 0, manually read and discard bytes
                        int read = gzipIn.read(buffer, 0, (int) (padding - skipped));
                        if (read == -1) {
                            break;
                        }
                        skipRead = read;
                    }
                    skipped += skipRead;
                }
            }
        }
    }

    private static int readFully(InputStream in, byte[ ] b, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int count = in.read(b, off + n, len - n);
            if (count < 0) {
                break;
            }
            n += count;
        }
        return n;
    }

    private static void deleteDirectoryRecursively(Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walk(dir)
                     .sorted(java.util.Comparator.reverseOrder())
                     .map(Path::toFile)
                     .forEach(File::delete);
            }
        } catch (Exception ignored) {}
    }
}