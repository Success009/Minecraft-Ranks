package com.p2ppvp.mod;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure join-gate and combatant decisions for spectating. No Minecraft imports so it is unit-testable.
 *
 * The host replaces the authorized spectator names from the matchmaker roster
 * (GET /api/spectate/roster). Names are compared case-insensitively.
 */
public final class SpectatorPolicy {
    public static final int MAX_SPECTATORS = 5;

    public enum Role { HOST, OPPONENT, SPECTATOR, DENIED }

    private static volatile Set<String> authorized = Collections.emptySet();
    private static volatile int cap = MAX_SPECTATORS;

    private SpectatorPolicy() {}

    private static String norm(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Replace the authorized set. Blank names are dropped; only the first {@code cap} names are kept. */
    public static synchronized void setAuthorized(Collection<String> names) {
        Set<String> next = new LinkedHashSet<>();
        if (names != null) {
            for (String n : names) {
                String k = norm(n);
                if (!k.isEmpty() && next.size() < cap) next.add(k);
            }
        }
        authorized = Collections.unmodifiableSet(next);
    }

    public static synchronized void clear() {
        authorized = Collections.emptySet();
        cap = MAX_SPECTATORS;
    }

    public static synchronized void setCap(int newCap) {
        cap = Math.max(0, newCap);
    }

    public static int cap() { return cap; }

    public static boolean isAuthorizedSpectator(String name) {
        String k = norm(name);
        return !k.isEmpty() && authorized.contains(k);
    }

    /**
     * Decide a joining player's role.
     * @param currentSpectators spectators already connected (not counting this player)
     */
    public static Role roleFor(String name, boolean isHost, String opponentName, boolean isMock, int currentSpectators) {
        if (isHost) return Role.HOST;
        boolean opponent = opponentName != null && !opponentName.isEmpty() && name != null && name.equalsIgnoreCase(opponentName);
        if (opponent || isMock) return Role.OPPONENT;
        if (isAuthorizedSpectator(name) && currentSpectators < cap) return Role.SPECTATOR;
        return Role.DENIED;
    }

    /** A player is a combatant (counts toward match start / can lose) unless they are an authorized spectator. */
    public static boolean isCombatant(String name, boolean isHost, String opponentName) {
        if (isHost) return true;
        if (opponentName != null && name != null && name.equalsIgnoreCase(opponentName)) return true;
        return !isAuthorizedSpectator(name);
    }
}
