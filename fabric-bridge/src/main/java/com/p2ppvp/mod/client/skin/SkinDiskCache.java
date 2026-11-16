package com.p2ppvp.mod.client.skin;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Saves downloaded skin PNGs on disk, one file per player and token: {root}/{kind}/{name}-{token}.png.
 * Pure java.nio (no Minecraft imports). Never throws: failures return null or false.
 * Only one file per player and kind is kept; saving a new token removes the older ones.
 */
public final class SkinDiskCache {
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9_]{1,16}");
    private static final long MAX_BYTES = 256L * 1024L;

    private final Path root;

    public SkinDiskCache(Path root) {
        this.root = root;
    }

    private static boolean validKind(String kind) {
        return "head".equals(kind) || "full".equals(kind);
    }

    private static boolean valid(String kind, String name, String token) {
        return validKind(kind) && name != null && SAFE_NAME.matcher(name).matches()
            && token != null && SAFE_TOKEN.matcher(token).matches();
    }

    private Path file(String kind, String name, String token) {
        return root.resolve(kind).resolve(SkinCache.cacheKey(name, token) + ".png");
    }

    /** Bytes saved for this exact name and token, or null. */
    public byte[] load(String kind, String name, String token) {
        String key = SkinCache.key(name);
        if (!valid(kind, key, token)) return null;
        try {
            Path f = file(kind, key, token);
            if (!Files.isRegularFile(f) || Files.size(f) > MAX_BYTES) return null;
            return Files.readAllBytes(f);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Save bytes for this name and token and delete older files of the same player and kind. */
    public boolean save(String kind, String name, String token, byte[] png) {
        String key = SkinCache.key(name);
        if (!valid(kind, key, token) || png == null || png.length == 0 || png.length > MAX_BYTES) return false;
        try {
            Path dir = root.resolve(kind);
            Files.createDirectories(dir);
            Path target = file(kind, key, token);
            Path tmp = dir.resolve(SkinCache.cacheKey(key, token) + ".png.tmp");
            Files.write(tmp, png);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            deleteOthers(dir, key, target);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Delete every saved file of a player (all kinds). */
    public void delete(String name) {
        String key = SkinCache.key(name);
        if (!SAFE_NAME.matcher(key).matches()) return;
        for (String kind : new String[] {"head", "full"}) {
            Path dir = root.resolve(kind);
            if (Files.isDirectory(dir)) deleteOthers(dir, key, null);
        }
    }

    private static void deleteOthers(Path dir, String key, Path keep) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, key + "-*.png")) {
            for (Path p : ds) {
                if (keep != null && p.equals(keep)) continue;
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
