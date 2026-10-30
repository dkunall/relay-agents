package com.relayagents.run.domain;

public enum RunMode {
    LIVE,
    /** Every model call, tool call, retrieval and approval comes from the source run's events. */
    REPLAY_RECORDED,
    /** Model calls and read-only tools run again; write tools and approvals still come from the source run. */
    REPLAY_LIVE;

    public boolean isReplay() {
        return this != LIVE;
    }
}
