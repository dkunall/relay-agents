package com.relayagents.tool.adapter.http;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowedHosts exact host names tenant HTTP tools may call; everything else is refused
 */
@ConfigurationProperties("relay.tools.http")
record HttpToolProperties(List<String> allowedHosts, Duration connectTimeout, int maxResponseBytes) {

    HttpToolProperties {
        allowedHosts = allowedHosts == null ? List.of()
            : allowedHosts.stream().filter(h -> h != null && !h.isBlank()).toList();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        maxResponseBytes = maxResponseBytes <= 0 ? 32 * 1024 : maxResponseBytes;
    }
}
