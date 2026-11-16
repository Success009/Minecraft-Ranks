package com.p2ppvp.mod.client.ui;

import com.p2ppvp.mod.client.social.SocialModels.KitRef;
import java.util.Set;

/** Turns the kit picker's selection into the single kit a challenge can use. No Minecraft imports. */
public final class ChallengeKit {
    private ChallengeKit() {}

    /** Result: either a kit to challenge with, or a short hint telling the player what to change. */
    public record Choice(KitRef kit, String label, String hint) {
        public boolean ok() { return kit != null; }
    }

    public static Choice from(Set<String> selected) {
        if (selected == null || selected.isEmpty() || selected.contains("Random")) {
            return new Choice(null, "none", "Pick one kit first");
        }
        if (selected.size() > 1) {
            return new Choice(null, "several", "Pick only one kit");
        }
        String k = selected.iterator().next();
        if (k.regionMatches(true, 0, "Community:", 0, 10) || k.regionMatches(true, 0, "Unofficial:", 0, 11)) {
            String name = k.substring(k.indexOf(':') + 1).trim();
            if (name.isEmpty()) return new Choice(null, "none", "Pick one kit first");
            return new Choice(new KitRef("community", name), "Community " + name, null);
        }
        if (k.regionMatches(true, 0, "Custom", 0, 6)) {
            return new Choice(null, "custom", "Custom kits can't be used in challenges yet");
        }
        return new Choice(new KitRef("official", k), "Ranked " + k, null);
    }

    private static final java.util.List<String> OFFICIAL = java.util.List.of("Crystal", "UHC", "Pot", "Mace", "Sword");

    /** Every kit a challenge may use for this selection. Random or empty means all official kits. Custom kits are skipped. */
    public static java.util.List<KitRef> candidates(Set<String> sel) {
        java.util.List<KitRef> out = new java.util.ArrayList<>();
        if (sel == null || sel.isEmpty() || sel.contains("Random")) {
            for (String k : OFFICIAL) out.add(new KitRef("official", k));
            return out;
        }
        for (String e : sel) {
            Choice c = from(Set.of(e));
            if (c.ok()) out.add(c.kit());
        }
        return out;
    }

    /** The kit to send: the only candidate, or one picked at random from several. */
    public static Choice resolve(Set<String> sel, java.util.Random rnd) {
        java.util.List<KitRef> c = candidates(sel);
        if (c.isEmpty()) return new Choice(null, "custom", "Custom kits can't be used in challenges yet");
        KitRef k = c.get(c.size() == 1 ? 0 : rnd.nextInt(c.size()));
        return new Choice(k, k.typeLabel() + " " + k.name(), null);
    }

    /** Read-only text for the Players screen. */
    public static String label(Set<String> sel) {
        java.util.List<KitRef> c = candidates(sel);
        if (c.isEmpty()) return "Custom kits can't be used in challenges yet";
        if (c.size() == 1) return c.get(0).typeLabel() + " " + c.get(0).name();
        if (sel == null || sel.isEmpty() || sel.contains("Random")) return "Ranked Random";
        StringBuilder b = new StringBuilder("Random of ");
        for (int i = 0; i < c.size(); i++) b.append(i > 0 ? ", " : "").append(c.get(i).name());
        return b.toString();
    }
}
