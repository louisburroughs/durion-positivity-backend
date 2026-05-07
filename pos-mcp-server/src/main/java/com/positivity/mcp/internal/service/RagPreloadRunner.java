package com.positivity.mcp.internal.service;

import com.positivity.mcp.service.StaticRagPreloadService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("alpha")
@Order(2)
public class RagPreloadRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RagPreloadRunner.class);

    private final StaticRagPreloadService staticRagPreloadService;

    public RagPreloadRunner(@NonNull StaticRagPreloadService staticRagPreloadService) {
        this.staticRagPreloadService = staticRagPreloadService;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        try {
            LOGGER.info("Starting static RAG preload");
            staticRagPreloadService.preloadAll();
            LOGGER.info("Static RAG preload complete");
        } catch (Exception exception) {
            LOGGER.warn("Static RAG preload failed (best-effort, service will continue)", exception);
        }
    }
}
