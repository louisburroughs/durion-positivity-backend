package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.discovery.OpenApiDocumentFetcher;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import com.positivity.mcp.service.ToolRegistrationService;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ToolRegistrationServiceImpl implements ToolRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistrationServiceImpl.class);

    private final McpServerProperties properties;
    private final OpenApiDocumentFetcher openApiDocumentFetcher;
    private final OpenApiToolMapper openApiToolMapper;
    private final McpAsyncServer mcpAsyncServer;

    public ToolRegistrationServiceImpl(
            @NonNull McpServerProperties properties,
            @NonNull OpenApiDocumentFetcher openApiDocumentFetcher,
            @NonNull OpenApiToolMapper openApiToolMapper,
            @NonNull McpAsyncServer mcpAsyncServer) {
        this.properties = properties;
        this.openApiDocumentFetcher = openApiDocumentFetcher;
        this.openApiToolMapper = openApiToolMapper;
        this.mcpAsyncServer = mcpAsyncServer;
    }

    @Override
    public @NonNull Mono<Void> registerDiscoveredTools() {
        long totalStartNanos = System.nanoTime();
        return openApiDocumentFetcher
                .fetchAggregateSpec()
                .switchIfEmpty(Mono.fromRunnable(() -> log.warn(
                        "Aggregate OpenAPI spec unavailable — skipping auto-discovered tool registration")))
                .flatMap(discovered -> {
                    var specifications =
                            openApiToolMapper.toAggregateToolSpecifications(discovered.baseUri(), discovered.openApi());
                    if (specifications.isEmpty()) {
                        log.warn(
                                "No MCP tools matched the configured allowlist in the aggregate spec. Path prefixes: {}",
                                properties.includedPathPrefixes());
                        return Mono.<Void>empty();
                    }

                    String toolNames = specifications.stream()
                            .map(specification -> specification.tool().name())
                            .collect(Collectors.joining(", "));

                    log.info("Registering {} MCP tools from gateway aggregate spec: {}", specifications.size(), toolNames);

                    return Flux.fromIterable(specifications)
                            .flatMap(this::addToolWithTiming)
                            .then(mcpAsyncServer.notifyToolsListChanged())
                            .doOnSuccess(ignored -> log.info(
                                    "Registered MCP tools from gateway aggregate spec in {} ms",
                                    elapsedMs(totalStartNanos)));
                })
                .onErrorResume(ex -> {
                    log.warn(
                            "Failed to register tools from gateway aggregate spec after {} ms: {}",
                            elapsedMs(totalStartNanos),
                            ex.getMessage());
                    return Mono.empty();
                });
    }

    private @NonNull Mono<Void> addToolWithTiming(McpServerFeatures.AsyncToolSpecification specification) {
        long startNanos = System.nanoTime();
        String toolName = specification.tool().name();
        return mcpAsyncServer
                .addTool(specification)
                .doOnSuccess(ignored -> log.info("Registered MCP tool {} in {} ms", toolName, elapsedMs(startNanos)));
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
