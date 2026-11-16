package com.p2ppvp.mod.client.social;

/** Single source for the matchmaker base URL used by the social layer. The daemon proxies this local port over tsnet. */
public final class MatcherConfig {
    public static final String BASE_URL = "http://127.0.0.1:8000";

    private MatcherConfig() {}
}
