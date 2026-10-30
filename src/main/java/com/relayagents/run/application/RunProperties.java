package com.relayagents.run.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param defaultMaxConcurrentRuns   used for tenants without a tenant_quota row
 * @param defaultDailyCostCapMicros  likewise
 */
@ConfigurationProperties("relay.runs")
public record RunProperties(Duration lease, int workers, Duration pollInterval, Duration approvalTtl,
                            int defaultMaxConcurrentRuns, long defaultDailyCostCapMicros) {

    public RunProperties {
        lease = lease == null ? Duration.ofSeconds(15) : lease;
        workers = workers <= 0 ? 4 : workers;
        pollInterval = pollInterval == null ? Duration.ofMillis(200) : pollInterval;
        approvalTtl = approvalTtl == null ? Duration.ofHours(24) : approvalTtl;
        defaultMaxConcurrentRuns = defaultMaxConcurrentRuns <= 0 ? 20 : defaultMaxConcurrentRuns;
        defaultDailyCostCapMicros = defaultDailyCostCapMicros <= 0 ? 5_000_000 : defaultDailyCostCapMicros;
    }
}
