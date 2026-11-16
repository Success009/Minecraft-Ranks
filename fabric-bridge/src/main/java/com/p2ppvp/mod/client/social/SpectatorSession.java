package com.p2ppvp.mod.client.social;

/**
 * Pure state machine for the client side of spectating. No Minecraft or network code.
 * IDLE -> JOINING (join request sent) -> SPECTATING (server confirmed, connect started) -> IDLE (left).
 */
public final class SpectatorSession {
    public enum State { IDLE, JOINING, SPECTATING }

    private State state = State.IDLE;
    private String matchId;
    private String hostIp;

    public synchronized State state() { return state; }
    public synchronized String matchId() { return matchId; }
    public synchronized String hostIp() { return hostIp; }
    public synchronized boolean isActive() { return state != State.IDLE; }

    /** Returns false if a session is already active (the request must not be sent). */
    public synchronized boolean begin(String matchId) {
        if (state != State.IDLE || matchId == null || matchId.isEmpty()) return false;
        this.state = State.JOINING;
        this.matchId = matchId;
        this.hostIp = null;
        return true;
    }

    /** Server accepted the join. Returns true if the caller should now connect to hostIp. */
    public synchronized boolean onJoined(String hostIp) {
        if (state != State.JOINING || hostIp == null || hostIp.isEmpty()) {
            return false;
        }
        this.hostIp = hostIp;
        this.state = State.SPECTATING;
        return true;
    }

    /** Server rejected the join, or the request failed. */
    public synchronized void onJoinFailed() {
        if (state == State.JOINING) reset();
    }

    /**
     * Called on disconnect. Returns true if /api/spectate/leave should be sent
     * (a join was sent or confirmed), then resets to IDLE.
     */
    public synchronized boolean onDisconnect() {
        boolean needsLeave = state != State.IDLE;
        reset();
        return needsLeave;
    }

    private void reset() {
        state = State.IDLE;
        matchId = null;
        hostIp = null;
    }
}
