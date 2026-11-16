package com.p2ppvp.mod.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure helpers for the leaderboard screen: filtering by name, paging, rank labels
 * and finding the local player's row. No Minecraft imports so it can be unit tested.
 */
public final class LeaderboardRules {
    private LeaderboardRules() {}

    /** Minimal row data the rules need. */
    public record Row(String name, int elo, int wins, int losses) {
        public int matches() { return wins + losses; }
    }

    /** Returns the rows whose name contains the query (case-insensitive). Blank query returns all rows. */
    public static List<Row> filter(List<Row> rows, String query) {
        if (rows == null) return List.of();
        if (query == null || query.isBlank()) return new ArrayList<>(rows);
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.name() != null && r.name().toLowerCase(Locale.ROOT).contains(q)) out.add(r);
        }
        return out;
    }

    /** Number of pages for the given row count and page size (at least 1). */
    public static int pageCount(int total, int pageSize) {
        if (pageSize <= 0) return 1;
        return Math.max(1, (total + pageSize - 1) / pageSize);
    }

    /** Clamps a page index into the valid range. */
    public static int clampPage(int page, int total, int pageSize) {
        return Math.max(0, Math.min(page, pageCount(total, pageSize) - 1));
    }

    /** Index range [from, to) of the rows on the given page. */
    public static int[] pageRange(int page, int total, int pageSize) {
        int p = clampPage(page, total, pageSize);
        int from = Math.min(total, p * Math.max(1, pageSize));
        int to = Math.min(total, from + Math.max(1, pageSize));
        return new int[] { from, to };
    }

    /** Rank text such as "#1". Plain ASCII, so the vanilla font always draws it. */
    public static String rankLabel(int zeroBasedIndex) {
        return "#" + (zeroBasedIndex + 1);
    }

    /**
     * Colour (ARGB) of a rank badge: gold, silver, bronze for the top three, grey after that.
     */
    public static int rankColor(int zeroBasedIndex) {
        return switch (zeroBasedIndex) {
            case 0 -> 0xFFFFD700;
            case 1 -> 0xFFC0C0C0;
            case 2 -> 0xFFCD7F32;
            default -> 0xFF888888;
        };
    }

    /** Index of the local player in the full list, or -1 when absent. Case-insensitive. */
    public static int indexOfPlayer(List<Row> rows, String playerName) {
        if (rows == null || playerName == null || playerName.isEmpty()) return -1;
        for (int i = 0; i < rows.size(); i++) {
            if (playerName.equalsIgnoreCase(rows.get(i).name())) return i;
        }
        return -1;
    }

    /** True when the local player's row is not on the shown page, so it should be pinned at the bottom. */
    public static boolean shouldPinSelf(int selfIndex, int page, int total, int pageSize) {
        if (selfIndex < 0) return false;
        int[] range = pageRange(page, total, pageSize);
        return selfIndex < range[0] || selfIndex >= range[1];
    }

    /** Shortens a name to fit a column, ending with "..." when it was cut. */
    public static String shortName(String name, int maxChars) {
        if (name == null) return "";
        if (maxChars < 4 || name.length() <= maxChars) return name;
        return name.substring(0, maxChars - 3) + "...";
    }
}
