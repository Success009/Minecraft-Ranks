package com.p2ppvp.mod.client.ui;

import com.p2ppvp.mod.client.social.SocialModels.LiveMatch;

/** Pure rules for the Spectate screen button. No Minecraft imports. */
public final class SpectateRules {
    private SpectateRules() {}

    public record ButtonState(boolean enabled, String label) {}

    /**
     * @param m            selected match, or null if none selected
     * @param me           this player's id
     * @param sessionActive true if a spectate join is already in progress/active
     */
    public static ButtonState buttonFor(LiveMatch m, String me, boolean sessionActive) {
        if (m == null) return new ButtonState(false, "Select a match");
        if (sessionActive) return new ButtonState(false, "Joining...");
        if (me != null && (me.equals(m.host()) || me.equals(m.guest()))) return new ButtonState(false, "Your match");
        if (m.full() || m.spectatorCount() >= m.maxSpectators()) return new ButtonState(false, "Full");
        return new ButtonState(true, "Spectate");
    }

    public static String countLabel(LiveMatch m) {
        return m.spectatorCount() + "/" + m.maxSpectators();
    }
}
