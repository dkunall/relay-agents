package com.relayagents.trigger.adapter.scheduling;

import com.relayagents.trigger.application.TriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class CronTriggerJob {

    private static final Logger log = LoggerFactory.getLogger(CronTriggerJob.class);

    private final TriggerService triggers;

    CronTriggerJob(TriggerService triggers) {
        this.triggers = triggers;
    }

    @Scheduled(fixedDelayString = "${relay.triggers.cron-poll:PT10S}")
    void fireDue() {
        for (TriggerService.Fire fire : triggers.claimDue(50)) {
            try {
                triggers.fire(fire);
            } catch (RuntimeException e) {
                log.warn("Cron trigger {} skipped its {} fire: {}", fire.trigger().id(), fire.scheduledAt(),
                    e.getMessage());
            }
        }
    }
}
