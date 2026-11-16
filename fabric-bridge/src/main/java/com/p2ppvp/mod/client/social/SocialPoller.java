package com.p2ppvp.mod.client.social;

import com.p2ppvp.mod.SpectatorPolicy;
import com.p2ppvp.mod.client.social.SocialModels.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Drives presence heartbeat + challenge polling on menu screens, and roster polling while hosting.
 * tick() is cheap and non-blocking; network work is async via SocialApi. Latest results are kept
 * in thread-safe state for the UI to read.
 */
public class SocialPoller {
    public enum Mode { IDLE, MENU, HOSTING }

    private final SocialApi api;
    private final PollScheduler scheduler;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicReference<PollResult> lastPoll = new AtomicReference<>(new PollResult(List.of(), List.of()));
    private final AtomicReference<Boolean> online = new AtomicReference<>(Boolean.FALSE);
    private final Supplier<String> tailscaleIp;
    private final long perfScore;

    public SocialPoller(SocialApi api, LongSupplier clock, Supplier<String> tailscaleIp) {
        this.api = api;
        this.scheduler = new PollScheduler(clock);
        this.tailscaleIp = tailscaleIp;
        long mb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        this.perfScore = Runtime.getRuntime().availableProcessors() * 1000L + mb;
    }

    public PollResult latest() { return lastPoll.get(); }

    public boolean isOnline() { return online.get(); }

    /** Remove and return outcome notifications already delivered, so the UI shows each once. */
    public synchronized PollResult consume() {
        PollResult r = lastPoll.getAndSet(new PollResult(List.of(), List.of()));
        return r;
    }

    /** Remove and return only the outcome notifications; the incoming-challenge list stays so the UI can see when one disappears. */
    public synchronized java.util.List<Outcome> consumeOutcomes() {
        PollResult old = lastPoll.get();
        if (old.outcomes().isEmpty()) return java.util.List.of();
        lastPoll.set(new PollResult(old.incoming(), java.util.List.of()));
        return old.outcomes();
    }

    public static Mode modeFor(boolean inWorld, boolean hostingMatch) {
        if (hostingMatch) return Mode.HOSTING;
        return inWorld ? Mode.IDLE : Mode.MENU;
    }

    public void tick(Mode mode, String playerId, String displayName) {
        scheduler.setEnabled(mode != Mode.IDLE);
        if (mode == Mode.IDLE || playerId == null || playerId.isEmpty()) return;
        if (!scheduler.shouldPoll()) return;
        if (!inFlight.compareAndSet(false, true)) return;

        if (mode == Mode.HOSTING) {
            api.roster(playerId).whenComplete((r, ex) -> {
                try {
                    if (ex == null && r.ok()) {
                        SpectatorPolicy.setAuthorized(r.value());
                        scheduler.onSuccess();
                    } else {
                        scheduler.onFailure();
                    }
                } finally {
                    inFlight.set(false);
                }
            });
            return;
        }

        api.heartbeat(playerId, displayName == null ? playerId : displayName, tailscaleIp.get(), perfScore)
            .thenCompose(hb -> {
                online.set(hb.ok());
                if (!hb.ok()) return java.util.concurrent.CompletableFuture.completedFuture(
                    ApiResult.<PollResult>failure(hb.code(), hb.message(), 0));
                return api.pollChallenges(playerId);
            })
            .whenComplete((r, ex) -> {
                try {
                    if (ex == null && r.ok()) {
                        merge(r.value());
                        scheduler.onSuccess();
                    } else {
                        scheduler.onFailure();
                    }
                } finally {
                    inFlight.set(false);
                }
            });
    }

    /** Keep undelivered outcomes; replace incoming with the freshest list. */
    private synchronized void merge(PollResult fresh) {
        PollResult old = lastPoll.get();
        java.util.List<Outcome> outs = new java.util.ArrayList<>(old.outcomes());
        outs.addAll(fresh.outcomes());
        lastPoll.set(new PollResult(fresh.incoming(), outs));
    }
}
