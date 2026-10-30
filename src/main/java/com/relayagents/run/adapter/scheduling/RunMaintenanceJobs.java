package com.relayagents.run.adapter.scheduling;

import com.relayagents.run.application.ApprovalRecord;
import com.relayagents.run.application.RunEngine;
import com.relayagents.run.application.RunProperties;
import com.relayagents.run.application.RunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps for work nobody will finish on their own: steps whose worker died, runs past their deadline, and
 * approvals nobody decided. Each item is handled in its own transaction under the run lock, so running
 * these on several instances at once is safe.
 */
@Component
class RunMaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(RunMaintenanceJobs.class);
    private static final int BATCH = 100;

    private final RunRepository runs;
    private final RunEngine engine;
    private final RunProperties properties;
    private final Clock clock;

    RunMaintenanceJobs(RunRepository runs, RunEngine engine, RunProperties properties, Clock clock) {
        this.runs = runs;
        this.engine = engine;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${relay.runs.reaper-interval:PT2S}")
    void reapExpiredLeases() {
        for (RunRepository.ClaimKey key : runs.expiredLeases(clock.instant(), BATCH)) {
            guarded("lease", key.runId(), () -> engine.reapLease(key));
        }
    }

    @Scheduled(fixedDelayString = "${relay.runs.reaper-interval:PT2S}")
    void enforceDeadlines() {
        for (UUID runId : runs.overdueRuns(clock.instant(), BATCH)) {
            guarded("deadline", runId, () -> engine.expireDeadline(runId));
        }
    }

    @Scheduled(fixedDelayString = "${relay.runs.approval-sweep-interval:PT30S}")
    void expireApprovals() {
        Instant cutoff = clock.instant().minus(properties.approvalTtl());
        for (ApprovalRecord approval : runs.pendingApprovalsCreatedBefore(cutoff, BATCH)) {
            guarded("approval", approval.runId(), () -> engine.expireApproval(approval));
        }
    }

    private static void guarded(String job, UUID runId, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("{} sweep failed for run {}", job, runId, e);
        }
    }
}
