package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.discovery.OpenApiDocumentFetcher;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.service.ToolRegistrationService;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class ToolRegistrationServiceImpl implements ToolRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistrationServiceImpl.class);

    private static final String DISCOVERED_WORKFLOW_STATE = "IDLE";

    private final McpServerProperties properties;
    private final OpenApiDocumentFetcher openApiDocumentFetcher;
    private final OpenApiToolMapper openApiToolMapper;
    private final McpAsyncServer mcpAsyncServer;
    private final ToolMetadataRepository toolMetadataRepository;
    private final String gatewayBaseUrl;

    public ToolRegistrationServiceImpl(
            @NonNull McpServerProperties properties,
            @NonNull OpenApiDocumentFetcher openApiDocumentFetcher,
            @NonNull OpenApiToolMapper openApiToolMapper,
            @NonNull McpAsyncServer mcpAsyncServer,
            @NonNull ToolMetadataRepository toolMetadataRepository,
            @Value("${mcp.server.gateway-base-url:http://api-gateway:8080}") @NonNull String gatewayBaseUrl) {
        this.properties = properties;
        this.openApiDocumentFetcher = openApiDocumentFetcher;
        this.openApiToolMapper = openApiToolMapper;
        this.mcpAsyncServer = mcpAsyncServer;
        this.toolMetadataRepository = toolMetadataRepository;
        // Persisted as each discovered op's service_id. Routing is via the gateway base URI (not a
        // Eureka service id): alpha's Eureka registry is empty, and facade tools already reach the
        // gateway by base URL. The Gate 3 executor (G3.2) will call handlerForBaseUri(this).
        this.gatewayBaseUrl = gatewayBaseUrl;
    }

    @Override
    public @NonNull Mono<Void> registerDiscoveredTools() {
        long totalStartNanos = System.nanoTime();
        warnIfIncludedServicesDeprecated();
        return openApiDocumentFetcher
                .fetchAggregateSpec()
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Aggregate OpenAPI spec unavailable — skipping auto-discovered tool registration");
                    return Mono.empty();
                }))
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

                    log.info(
                            "Registering {} MCP tools from gateway aggregate spec: {}",
                            specifications.size(),
                            toolNames);

                    return persistDiscoveredOperations(discovered.openApi())
                            .then(Flux.fromIterable(specifications)
                                    .flatMap(this::addToolWithTiming)
                                    .then(mcpAsyncServer.notifyToolsListChanged()))
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

    /**
     * Gate 3 (G3.1): persists each discovered operation as a {@code source='openapi'} {@code mcp_tool}
     * row and maps it to the IDLE workflow so it can be selected. Runs off the event loop
     * (boundedElastic) because the upsert is blocking JDBC. Embeddings are backfilled by
     * {@code ToolEmbeddingInitializer}; required permissions are seeded separately (admin / #785), so
     * until then discovered ops are fail-closed (never selected without a permission grant).
     */
    private @NonNull Mono<Void> persistDiscoveredOperations(@NonNull OpenAPI openApi) {
        return Mono.fromRunnable(() -> {
                    List<DiscoveredOperation> operations =
                            openApiToolMapper.toDiscoveredOperations(gatewayBaseUrl, openApi);
                    int persisted = 0;
                    for (DiscoveredOperation operation : operations) {
                        String path = operation.httpPath();
                        if (path == null) {
                            continue;
                        }
                        try {
                            UUID toolId = toolMetadataRepository.upsertDiscoveredOperation(
                                    operation, OpenApiToolMapper.extractDomain(path));
                            toolMetadataRepository.linkToolToWorkflow(toolId, DISCOVERED_WORKFLOW_STATE);
                            persisted++;
                        } catch (RuntimeException exception) {
                            log.warn(
                                    "Failed to persist discovered openapi op {}: {}",
                                    operation.name(),
                                    exception.getMessage());
                        }
                    }
                    log.info(
                            "Persisted {} discovered openapi ops (source='openapi', {} workflow); "
                                    + "embeddings backfilled by ToolEmbeddingInitializer, permissions seeded separately",
                            persisted,
                            DISCOVERED_WORKFLOW_STATE);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void warnIfIncludedServicesDeprecated() {
        if (!properties.includedServices().isEmpty()
                && properties.aggregateSpecUrl() != null
                && !properties.aggregateSpecUrl().isBlank()) {
            log.warn("mcp.server.included-services is configured but has no effect in aggregate-first discovery mode "
                    + "(aggregate-spec-url is set). included-services is deprecated for aggregate-first "
                    + "discovery; use mcp.server.included-path-prefixes instead.");
        }
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
