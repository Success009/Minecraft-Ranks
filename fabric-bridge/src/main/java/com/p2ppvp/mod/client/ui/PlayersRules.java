package com.p2ppvp.mod.client.ui;

import com.p2ppvp.mod.client.social.SocialModels.OnlinePlayer;
import java.util.List;

/** Pure rules for the Players screen. No Minecraft imports. */
public final class PlayersRules {
    private PlayersRules() {}

    public static final List<String> OFFICIAL_KITS = List.of("Crystal", "UHC", "Pot", "Mace", "Sword");

    public record ButtonState(boolean enabled, String label) {}

    public static ButtonState buttonFor(OnlinePlayer p, String me, boolean apiOnline) {
        if (p == null) return new ButtonState(false, "Select a player");
        if (me != null && me.equals(p.playerId())) return new ButtonState(false, "That's you");
        if (!apiOnline) return new ButtonState(false, "Offline");
        if (p.cooldown() > 0) return new ButtonState(false, "Wait " + (int) Math.ceil(p.cooldown()) + "s");
        if (!p.challengeable()) return new ButtonState(false, "Unavailable");
        return new ButtonState(true, "Challenge");
    }

    /** Next official kit in the cycle; unknown or null starts at the first. */
    public static String nextKit(String current) {
        int i = current == null ? -1 : OFFICIAL_KITS.indexOf(current);
        return OFFICIAL_KITS.get((i + 1) % OFFICIAL_KITS.size());
    }

    public static String statusColor(String status) {
        String s = status == null ? "" : status.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("match")) return "§c";
        if (s.contains("queue")) return "§e";
        return "§a";
    }
}
