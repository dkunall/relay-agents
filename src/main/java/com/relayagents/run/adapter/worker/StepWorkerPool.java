package com.relayagents.run.adapter.worker;

import com.relayagents.run.application.FencedOutException;
import com.relayagents.run.application.RunEngine;
import com.relayagents.run.application.RunProperties;
import com.relayagents.run.application.StepFence;
import com.relayagents.run.application.StepOutcome;
import com.relayagents.run.application.StepRunner;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Worker loops that claim ready steps from Postgres. Several instances can run side by side: claims use
 * SKIP LOCKED, leases are heartbeated, and an outcome from a worker whose lease was taken over is dropped.
 */
@Component
@ConditionalOnProperty(name = "relay.runs.workers-enabled", havingValue = "true", matchIfMissing = true)
class StepWorkerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StepWorkerPool.class);

    private final RunEngine engine;
    private final StepRunner runner;
    private final RunProperties properties;
    private final String instance = UUID.randomUUID().toString().substring(0, 8);
    private ExecutorService loops;
    private ExecutorService steps;
    private ScheduledExecutorService heartbeats;
    private volatile boolean running;

    StepWorkerPool(RunEngine engine, StepRunner runner, RunProperties properties) {
        this.engine = engine;
        this.runner = runner;
        this.properties = properties;
    }

    @Override
    public void start() {
        running = true;
        loops = Executors.newVirtualThreadPerTaskExecutor();
        steps = Executors.newVirtualThreadPerTaskExecutor();
        heartbeats = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("lease-heartbeat").factory());
        for (int i = 0; i < properties.workers(); i++) {
            String workerId = "worker-" + instance + "-" + i;
            loops.submit(() -> loop(workerId));
        }
        log.info("Started {} step workers", properties.workers());
    }

    @Override
    public void stop() {
        running = false;
        loops.shutdown();
        try {
            if (!loops.awaitTermination(10, TimeUnit.SECONDS)) {
                loops.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        steps.shutdownNow();
        heartbeats.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop(String workerId) {
        while (running) {
            try {
                Optional<StepFence> claimed = engine.claim(workerId, properties.lease());
                if (claimed.isPresent()) {
                    execute(claimed.get());
                } else {
                    Thread.sleep(properties.pollInterval());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Worker {} loop error", workerId, e);
                sleepQuietly(properties.pollInterval());
            }
        }
    }

    private void execute(StepFence fence) throws InterruptedException {
        Duration timeout = runner.timeout(fence);
        Future<StepOutcome> work = steps.submit(() -> runner.run(fence));
        long every = Math.max(properties.lease().toMillis() / 3, 100);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try {
                if (!engine.heartbeat(fence, properties.lease())) {
                    work.cancel(true);
                }
            } catch (RuntimeException e) {
                log.warn("Heartbeat for {}/{} failed", fence.runId(), fence.stepId(), e);
            }
        }, every, every, TimeUnit.MILLISECONDS);

        StepOutcome outcome;
        try {
            outcome = work.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            work.cancel(true);
            outcome = new StepOutcome.Failed("step timed out after " + timeout.toMillis() + " ms", true);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof FencedOutException) {
                log.info("{}", e.getCause().getMessage());
                return;
            }
            outcome = new StepOutcome.Failed(String.valueOf(e.getCause()), true);
        } catch (CancellationException e) {
            log.info("Step {}/{} cancelled after losing its lease", fence.runId(), fence.stepId());
            return;
        } finally {
            heartbeat.cancel(false);
        }
        engine.complete(fence, outcome);
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
