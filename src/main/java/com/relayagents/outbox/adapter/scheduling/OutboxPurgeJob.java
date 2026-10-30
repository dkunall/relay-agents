package com.relayagents.outbox.adapter.scheduling;

import com.relayagents.outbox.application.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class OutboxPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxPurgeJob.class);

    private final OutboxRepository repository;
    private final Clock clock;
    private final Duration retention;

    OutboxPurgeJob(OutboxRepository repository, Clock clock,
                   @Value("${relay.outbox.retention:P7D}") Duration retention) {
        this.repository = repository;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${relay.outbox.purge-interval:PT1H}")
    void purge() {
        Instant cutoff = clock.instant().minus(retention);
        int purged = repository.purgePublishedBefore(cutoff);
        if (purged > 0) {
            log.info("Purged {} published outbox rows", purged);
        }
    }
}
