package com.p2ppvp.mod.client.skin;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Downloads heads and full skins from the matchmaker and registers them as textures.
 * Network work is async; texture registration runs on the render thread.
 * Downloaded PNGs are also saved on disk, so a launch only needs the small token lookup.
 * Never throws: until a skin arrives, lookups return null and the UI draws its fallback.
 */
public final class SkinTextures {
    /** A cached head is verified against the server at most this often. */
    private static final long HEAD_RECHECK_MS = 30_000L;

    private static final SkinCache CACHE = new SkinCache(30_000L, HEAD_RECHECK_MS);
    private static final SkinApi API = new SkinApi();
    private static final Map<String, Identifier> HEADS = new ConcurrentHashMap<>();
    private static final Map<String, Identifier> FULLS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();
    private static volatile long lastRequestMs = 0L;
    private static volatile SkinDiskCache disk;

    private SkinTextures() {}

    private static SkinDiskCache disk() {
        SkinDiskCache d = disk;
        if (d == null) {
            try {
                Path root = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("p2ppvp").resolve("skin_cache");
                d = new SkinDiskCache(root);
            } catch (Throwable t) {
                return null;
            }
            disk = d;
        }
        return d;
    }

    /** Head texture of a player if downloaded, else null. */
    public static Identifier headTexture(String name) {
        return HEADS.get(SkinCache.key(name));
    }

    /** Full skin texture of a player if downloaded, else null. */
    public static Identifier fullTexture(String name) {
        return FULLS.get(SkinCache.key(name));
    }

    private static final Map<String, Boolean> SLIMS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAZY_ASKED = new ConcurrentHashMap<>();

    /** True if the downloaded full skin of this player uses slim (3 px) arms. */
    public static boolean isSlim(String name) {
        return Boolean.TRUE.equals(SLIMS.get(SkinCache.key(name)));
    }

    /**
     * Called when a player is rendered. Once a skin is known the server is not asked again
     * (match start and upload refresh it). While none is known, asks at most every 30 s.
     */
    public static void requestFullLazy(String name) {
        String key = SkinCache.key(name);
        if (key.isEmpty()) return;
        if (FULLS.containsKey(key)) return;
        long now = System.currentTimeMillis();
        Long last = LAZY_ASKED.get(key);
        if (last != null && now - last < 30_000L) return;
        LAZY_ASKED.put(key, now);
        requestFull(key);
    }

    /** Ask for the heads of the players currently on screen. Cheap to call every frame: throttled to one request per 2 s, and verified players are skipped for 30 s. */
    public static void requestHeads(Collection<String> visible) {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < 2000L) return;
        List<String> names;
        synchronized (CACHE) {
            names = CACHE.namesToQuery(visible, now);
        }
        if (names.isEmpty()) return;
        lastRequestMs = now;
        API.tokens(names).thenAccept(tokens -> {
            List<String> stale;
            synchronized (CACHE) {
                stale = CACHE.staleAfterLookup(names, tokens, System.currentTimeMillis());
            }
            for (String n : stale) fetch("head", n, tokens.get(n), HEADS);
        });
    }

    /** Fetch a player's full skin (used at match start). Re-downloads only when the token changed. */
    public static void requestFull(String name) {
        String key = SkinCache.key(name);
        if (key.isEmpty()) return;
        API.tokens(List.of(key)).thenAccept(tokens -> {
            String tok = tokens.get(key);
            if (tok != null && !tok.equals(FULL_TOKENS.get(key))) fetch("full", key, tok, FULLS);
        });
    }

    /** Forget cached state for a player, for example after the local player uploads a new skin. */
    public static void invalidate(String name) {
        synchronized (CACHE) {
            CACHE.invalidate(name);
        }
        String key = SkinCache.key(name);
        FULL_TOKENS.remove(key);
        SkinDiskCache d = disk();
        if (d != null) {
            CompletableFuture.runAsync(() -> d.delete(key));
        }
        lastRequestMs = 0L;
        LAZY_ASKED.remove(key);
    }

    private static final Map<String, String> FULL_TOKENS = new ConcurrentHashMap<>();

    private static void fetch(String kind, String name, String token, Map<String, Identifier> target) {
        String key = SkinCache.key(name);
        String flight = kind + ":" + SkinCache.cacheKey(key, token);
        if (IN_FLIGHT.putIfAbsent(flight, Boolean.TRUE) != null) return;
        CompletableFuture<byte[]> source = CompletableFuture.supplyAsync(() -> {
            SkinDiskCache d = disk();
            return d == null ? null : d.load(kind, key, token);
        }).thenCompose(saved -> {
            if (saved != null) return CompletableFuture.completedFuture(saved);
            return API.download(kind, key).thenApply(bytes -> {
                SkinDiskCache d = disk();
                if (bytes != null && d != null) d.save(kind, key, token, bytes);
                return bytes;
            });
        });
        source.thenAccept(bytes -> {
            try {
                if (bytes == null) return;
                NativeImage img = NativeImage.read(bytes);
                if (kind.equals("full")) {
                    SLIMS.put(key, SkinModel.isSlim(img.getPixelsABGR(), img.getWidth(), img.getHeight()));
                }
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> register(kind, key, token, img, target));
            } catch (Exception ignored) {
            } finally {
                IN_FLIGHT.remove(flight);
            }
        }).exceptionally(ex -> {
            IN_FLIGHT.remove(flight);
            return null;
        });
    }

    private static void register(String kind, String key, String token, NativeImage img, Map<String, Identifier> target) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Identifier id = Identifier.fromNamespaceAndPath("p2ppvp", "skins/" + kind + "/" + SkinCache.cacheKey(key, token));
            mc.getTextureManager().register(id, new DynamicTexture(() -> "p2ppvp skin " + key, img));
            Identifier old = target.put(key, id);
            if (old != null && !old.equals(id)) mc.getTextureManager().release(old);
            if (kind.equals("full")) FULL_TOKENS.put(key, token);
            if (kind.equals("head")) {
                synchronized (CACHE) {
                    CACHE.markCached(key, token);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
