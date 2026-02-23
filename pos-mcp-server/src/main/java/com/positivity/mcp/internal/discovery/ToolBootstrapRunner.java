package com.positivity.mcp.internal.discovery;

import com.positivity.mcp.service.ToolRegistrationService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ToolBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolBootstrapRunner.class);

    private final ToolRegistrationService toolRegistrationService;

    ToolBootstrapRunner(@NonNull ToolRegistrationService toolRegistrationService) {
        this.toolRegistrationService = toolRegistrationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Discovering services and registering MCP tools via Eureka");
        toolRegistrationService.registerDiscoveredTools().blockOptional();
    }
}
