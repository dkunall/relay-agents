package com.relayagents.trigger.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TriggerRepository {

    void insert(Trigger trigger);

    Optional<Trigger> find(String tenant, UUID id);

    /**
     * Webhook deliveries arrive without a tenant; the signature check is what scopes them.
     */
    Optional<Trigger> findById(UUID id);

    List<Trigger> list(String tenant);

    void setEnabled(String tenant, UUID id, boolean enabled);

    /**
     * Locks due cron triggers, skipping ones another instance is firing right now.
     */
    List<Trigger> lockDue(Instant now, int limit);

    void setNextFire(UUID id, Instant nextFireAt);
}
