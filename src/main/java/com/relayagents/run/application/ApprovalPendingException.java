package com.relayagents.run.application;

import java.util.UUID;

/**
 * Unwinds a step that reached a call needing a human. The step parks and runs again once decided; its
 * journal makes the second run skip everything up to that call.
 */
public class ApprovalPendingException extends RuntimeException {

    private final UUID approvalId;

    public ApprovalPendingException(UUID approvalId) {
        super("waiting for approval " + approvalId, null, false, false);
        this.approvalId = approvalId;
    }

    public UUID approvalId() {
        return approvalId;
    }
}
