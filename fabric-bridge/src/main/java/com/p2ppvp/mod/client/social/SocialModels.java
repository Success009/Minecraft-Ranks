package com.p2ppvp.mod.client.social;

/** Plain immutable models for the social API. No Minecraft imports. */
public final class SocialModels {
    private SocialModels() {}

    public record KitRef(String type, String name) {
        public String typeLabel() {
            if (type == null || type.isEmpty()) return "Custom";
            if (type.equalsIgnoreCase("official")) return "Ranked";
            return Character.toUpperCase(type.charAt(0)) + type.substring(1).toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record OnlinePlayer(String playerId, String name, String status, int points, boolean challengeable, double cooldown) {}

    public record LiveMatch(String matchId, String host, String guest, KitRef kit, int spectatorCount, int maxSpectators, boolean full) {}

    public record ChallengeInfo(String challengeId, String sender, KitRef kit, double secondsLeft) {}

    public record Outcome(String challengeId, String result, String target, KitRef kit) {}

    public record PollResult(java.util.List<ChallengeInfo> incoming, java.util.List<Outcome> outcomes) {}

    /** Result of an API call: either ok with a value or an error code (and optional retry-after). */
    public record ApiResult<T>(boolean ok, T value, String code, String message, double retryAfter) {
        public static <T> ApiResult<T> success(T v) { return new ApiResult<>(true, v, null, null, 0); }
        public static <T> ApiResult<T> failure(String code, String message, double retryAfter) {
            return new ApiResult<>(false, null, code, message, retryAfter);
        }
    }
}
