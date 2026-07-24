package com.positivity.mcp.internal.discovery;

import com.positivity.mcp.service.ToolRegistrationService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * #645: optional periodic re-discovery of gateway OpenAPI tools, so newly added or changed backend
 * operations are picked up without restarting pos-mcp-server.
 *
 * <p>Disabled by default; opt in with {@code mcp.server.discovery-refresh.enabled=true} and tune the
 * cadence via {@code mcp.server.discovery-refresh.interval-ms} (default 5 min). Re-registration is
 * idempotent (see {@code ToolRegistrationServiceImpl#addToolWithTiming}), so a refresh safely re-adds
 * existing tools and picks up new ones. The registration chain is fail-soft, so a refresh never
 * disrupts serving.
 */
@Component
@ConditionalOnProperty(prefix = "mcp.server.discovery-refresh", name = "enabled", havingValue = "true")
public class DiscoveryRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryRefreshScheduler.class);

    private final ToolRegistrationService toolRegistrationService;

    public DiscoveryRefreshScheduler(@NonNull ToolRegistrationService toolRegistrationService) {
        this.toolRegistrationService = toolRegistrationService;
    }

    @Scheduled(
            fixedDelayString = "${mcp.server.discovery-refresh.interval-ms:300000}",
            initialDelayString = "${mcp.server.discovery-refresh.interval-ms:300000}")
    public void refresh() {
        log.debug("Periodic MCP tool discovery refresh starting");
        toolRegistrationService
                .registerDiscoveredTools()
                .doOnError(ex -> log.warn("Periodic MCP tool discovery refresh failed: {}", ex.getMessage()))
                .onErrorComplete()
                .subscribe();
    }
}
