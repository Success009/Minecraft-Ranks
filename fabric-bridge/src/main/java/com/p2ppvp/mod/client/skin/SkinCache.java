package com.p2ppvp.mod.client.skin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure bookkeeping for downloaded skins (no Minecraft imports).
 * Tracks which token each player's cached head/skin was downloaded for, decides what needs
 * downloading, and remembers recent misses so absent skins are not requested again and again.
 */
public final class SkinCache {
    public static final int MAX_BATCH = 64;

    private final Map<String, String> cachedToken = new HashMap<>();
    private final Map<String, Long> missUntil = new HashMap<>();
    private final long missTtlMillis;

    public SkinCache(long missTtlMillis) {
        this.missTtlMillis = missTtlMillis;
    }

    public static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Cache file / texture key: lowercase name, separator, token. */
    public static String cacheKey(String name, String token) {
        return key(name) + "-" + token;
    }

    /** Names worth asking the server about now: valid, not recently missing, deduplicated, capped at MAX_BATCH. */
    public List<String> namesToQuery(Collection<String> visible, long now) {
        Set<String> out = new LinkedHashSet<>();
        if (visible == null) return new ArrayList<>();
        for (String n : visible) {
            String k = key(n);
            if (k.isEmpty() || k.length() > 16) continue;
            Long until = missUntil.get(k);
            if (until != null && now < until) continue;
            out.add(k);
            if (out.size() >= MAX_BATCH) break;
        }
        return new ArrayList<>(out);
    }

    /**
     * Compare the server's token map for the queried names with what is cached.
     * Returns the names whose head must be (re)downloaded. Queried names absent from the map
     * are marked as missing for missTtlMillis.
     */
    public List<String> staleAfterLookup(Collection<String> queried, Map<String, String> serverTokens, long now) {
        List<String> stale = new ArrayList<>();
        for (String n : queried) {
            String k = key(n);
            String tok = serverTokens == null ? null : serverTokens.get(k);
            if (tok == null || tok.isEmpty()) {
                missUntil.put(k, now + missTtlMillis);
                cachedToken.remove(k);
                continue;
            }
            missUntil.remove(k);
            if (!tok.equals(cachedToken.get(k))) stale.add(k);
        }
        return stale;
    }

    public void markCached(String name, String token) {
        cachedToken.put(key(name), token);
    }

    /** The token the cached copy was downloaded for, or null. */
    public String tokenOf(String name) {
        return cachedToken.get(key(name));
    }

    public boolean hasSkin(String name) {
        return cachedToken.containsKey(key(name));
    }

    /** Forget a player's cached state (for example after the local player uploads a new skin). */
    public void invalidate(String name) {
        String k = key(name);
        cachedToken.remove(k);
        missUntil.remove(k);
    }
}
