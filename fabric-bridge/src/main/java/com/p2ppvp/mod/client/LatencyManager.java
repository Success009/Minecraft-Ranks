package com.p2ppvp.mod.client;

import com.p2ppvp.mod.DebugLogger;

public class LatencyManager {
    private static int activeDelayMs = 0;

    public static void setActiveDelay(int delayMs) {
        activeDelayMs = delayMs;
        DebugLogger.log("[LATENCY] Active packet delay set to: " + delayMs + " ms");
    }

    public static int getActiveDelay() {
        return activeDelayMs;
    }
}
