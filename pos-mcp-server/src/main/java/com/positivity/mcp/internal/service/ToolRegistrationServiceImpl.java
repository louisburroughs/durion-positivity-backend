package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.discovery.OpenApiDocumentFetcher;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.service.ToolRegistrationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import java.util.Set;
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
    // #645: discovery/registration metrics. Meter names are dot-cased so Prometheus exposes them as
    // tools_discovered_total / tools_registered_total.
    private final Counter toolsDiscoveredTotal;
    private final Counter toolsRegisteredTotal;
    // #1121: orphan openapi rows pruned to reconcile the persisted set with the current spec.
    private final Counter toolsPrunedTotal;

    public ToolRegistrationServiceImpl(
            @NonNull McpServerProperties properties,
            @NonNull OpenApiDocumentFetcher openApiDocumentFetcher,
            @NonNull OpenApiToolMapper openApiToolMapper,
            @NonNull McpAsyncServer mcpAsyncServer,
            @NonNull ToolMetadataRepository toolMetadataRepository,
            @Value("${mcp.server.gateway-base-url:http://api-gateway:8080}") @NonNull String gatewayBaseUrl,
            @NonNull MeterRegistry meterRegistry) {
        this.properties = properties;
        this.openApiDocumentFetcher = openApiDocumentFetcher;
        this.openApiToolMapper = openApiToolMapper;
        this.mcpAsyncServer = mcpAsyncServer;
        this.toolMetadataRepository = toolMetadataRepository;
        // Persisted as each discovered op's service_id. Routing is via the gateway base URI (not a
        // Eureka service id): alpha's Eureka registry is empty, and facade tools already reach the
        // gateway by base URL. The Gate 3 executor (G3.2) will call handlerForBaseUri(this).
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.toolsDiscoveredTotal = Counter.builder("tools.discovered")
                .description("OpenAPI operations discovered from the gateway aggregate and matched to MCP tools")
                .register(meterRegistry);
        this.toolsRegisteredTotal = Counter.builder("tools.registered")
                .description("MCP tools successfully registered from discovery")
                .register(meterRegistry);
        this.toolsPrunedTotal = Counter.builder("tools.pruned")
                .description("Stale openapi-discovered mcp_tool rows pruned to match the current spec (#1121)")
                .register(meterRegistry);
    }

    @Override
    public @NonNull Mono<Void> registerDiscoveredTools() {
        long totalStartNanos = System.nanoTime();
        warnIfIncludedServicesDeprecated();
        return openApiDocumentFetcher
                .fetchAggregateSpec()
                .flatMap(discovered -> registerAggregate(discovered, totalStartNanos))
                // Aggregate unavailable (fetch fail-soft to empty) or matched no tools → fall back to
                // per-service Eureka discovery (#645), so a down/empty gateway aggregate endpoint does
                // not leave the server with zero discovered tools when the services are individually
                // reachable via the registry.
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(registered -> Boolean.TRUE.equals(registered)
                        ? Mono.<Void>empty()
                        : registerViaPerServiceFallback(totalStartNanos))
                .onErrorResume(ex -> {
                    log.warn(
                            "Failed to register discovered tools after {} ms: {}",
                            elapsedMs(totalStartNanos),
                            ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Registers tools from the gateway aggregate spec. Returns {@code true} when at least one tool was
     * registered, {@code false} when the aggregate matched no tools (so the caller falls back to
     * per-service Eureka discovery).
     */
    private @NonNull Mono<Boolean> registerAggregate(
            OpenApiDocumentFetcher.@NonNull DiscoveredOpenApi discovered, long totalStartNanos) {
        var specifications =
                openApiToolMapper.toAggregateToolSpecifications(discovered.baseUri(), discovered.openApi());
        if (specifications.isEmpty()) {
            log.warn(
                    "No MCP tools matched the configured allowlist in the aggregate spec. Path prefixes: {}",
                    properties.includedPathPrefixes());
            return Mono.just(Boolean.FALSE);
        }

        String toolNames = specifications.stream()
                .map(specification -> specification.tool().name())
                .collect(Collectors.joining(", "));

        toolsDiscoveredTotal.increment(specifications.size());
        log.info("Registering {} MCP tools from gateway aggregate spec: {}", specifications.size(), toolNames);

        return persistDiscoveredOperations(discovered.openApi())
                .then(Flux.fromIterable(specifications)
                        .flatMap(this::addToolWithTiming)
                        .then(mcpAsyncServer.notifyToolsListChanged()))
                .doOnSuccess(ignored -> log.info(
                        "Registered MCP tools from gateway aggregate spec in {} ms", elapsedMs(totalStartNanos)))
                .thenReturn(Boolean.TRUE);
    }

    /**
     * #645 fallback: when aggregate discovery yields no tools, discover each candidate service's own
     * OpenAPI directly through Eureka ({@link OpenApiDocumentFetcher#fetchForService}) and register the
     * resulting tools. Candidate services come from {@code mcp.server.included-services} when set,
     * otherwise every registered service except the gateway. Fail-soft per service and overall: a
     * service that is unreachable or serves no matching paths is skipped, never aborting the batch.
     */
    private @NonNull Mono<Void> registerViaPerServiceFallback(long totalStartNanos) {
        List<String> serviceIds = openApiDocumentFetcher.fallbackServiceIds();
        if (serviceIds.isEmpty()) {
            log.warn("Per-service Eureka discovery fallback found no candidate services; no tools registered");
            return Mono.empty();
        }
        log.info(
                "Aggregate discovery yielded no tools; falling back to per-service Eureka discovery for {} service(s): {}",
                serviceIds.size(),
                String.join(", ", serviceIds));
        return Flux.fromIterable(serviceIds)
                .flatMap(serviceId -> openApiDocumentFetcher
                        .fetchForService(serviceId)
                        .map(discovered -> openApiToolMapper.toToolSpecifications(
                                discovered.serviceId(), discovered.baseUri(), discovered.openApi()))
                        .onErrorResume(ex -> {
                            log.warn("Per-service discovery failed for {}: {}", serviceId, ex.getMessage());
                            return Mono.empty();
                        }))
                .flatMapIterable(specs -> specs)
                .collectList()
                .flatMap(specifications -> {
                    if (specifications.isEmpty()) {
                        log.warn("Per-service Eureka fallback registered no tools (no reachable service specs)");
                        return Mono.<Void>empty();
                    }
                    toolsDiscoveredTotal.increment(specifications.size());
                    log.info("Registering {} MCP tools from per-service Eureka fallback", specifications.size());
                    return Flux.fromIterable(specifications)
                            .flatMap(this::addToolWithTiming)
                            .then(mcpAsyncServer.notifyToolsListChanged())
                            .doOnSuccess(ignored -> log.info(
                                    "Registered per-service Eureka fallback MCP tools in {} ms",
                                    elapsedMs(totalStartNanos)));
                });
    }

    /**
     * Gate 3 (G3.1): persists each discovered operation as a {@code source='openapi'} {@code mcp_tool}
     * row and maps it to the IDLE workflow so it can be selected. Runs off the event loop
     * (boundedElastic) because the upsert is blocking JDBC. Embeddings are backfilled by
     * {@code ToolEmbeddingInitializer}; required permissions are granted from each op's
     * {@code x-required-permissions} extension (#781, fail-closed — an op with no extension gets no
     * grant and is never selected until curated via the #785 admin surface).
     */
    private @NonNull Mono<Void> persistDiscoveredOperations(@NonNull OpenAPI openApi) {
        return Mono.fromRunnable(() -> {
                    List<DiscoveredOperation> operations =
                            openApiToolMapper.toDiscoveredOperations(gatewayBaseUrl, openApi);
                    Set<String> discoveredNames = discoveredNames(operations);
                    int persisted = persistAll(operations);
                    log.info(
                            "Persisted {} discovered openapi ops (source='openapi', {} workflow); "
                                    + "embeddings backfilled by ToolEmbeddingInitializer, permissions granted from "
                                    + "x-required-permissions (fail-closed when absent)",
                            persisted,
                            DISCOVERED_WORKFLOW_STATE);
                    pruneStaleOperations(persisted, discoveredNames);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * Names of every op discovered this run — the desired persisted set for the #1121 prune. {@code
     * name} is {@code @NonNull} by contract; the filter is a defensive guard so a stray null can never
     * enter the keep-set and turn the prune's {@code NOT IN (...)} into a silent no-op.
     */
    private static @NonNull Set<String> discoveredNames(@NonNull List<DiscoveredOperation> operations) {
        return operations.stream()
                .map(DiscoveredOperation::name)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** Persists every op with a usable path, skipping and warning past any single failure. Returns the count persisted. */
    private int persistAll(@NonNull List<DiscoveredOperation> operations) {
        int persisted = 0;
        for (DiscoveredOperation operation : operations) {
            if (operation.httpPath() != null && persistOne(operation)) {
                persisted++;
            }
        }
        return persisted;
    }

    /**
     * Persists a single discovered op: upserts the {@code mcp_tool} row, links it to the discovered
     * workflow, and grants its {@code x-required-permissions} (#781, fail-closed — absent extension
     * grants nothing, so the op stays unselectable until curated via #785 admin). A failure is logged
     * and swallowed so one bad op never aborts the batch. Returns {@code true} on success.
     */
    private boolean persistOne(@NonNull DiscoveredOperation operation) {
        try {
            UUID toolId = toolMetadataRepository.upsertDiscoveredOperation(
                    operation, OpenApiToolMapper.extractDomain(operation.httpPath()));
            toolMetadataRepository.linkToolToWorkflow(toolId, DISCOVERED_WORKFLOW_STATE);
            for (String permissionCode : operation.requiredPermissions()) {
                toolMetadataRepository.addToolPermission(toolId, permissionCode);
            }
            return true;
        } catch (RuntimeException exception) {
            log.warn("Failed to persist discovered openapi op {}: {}", operation.name(), exception.getMessage());
            return false;
        }
    }

    /**
     * #1121: reconcile — delete any source='openapi' row absent from this run (orphans left by a spec
     * change or discovery-mode switch, since persistence is otherwise upsert-only). Guarded on {@code
     * persisted > 0} so a run that wrote nothing (bad/empty spec, DB trouble) can never wipe the
     * catalog; {@code pruneDiscoveredOperationsExcept} also no-ops on an empty set.
     */
    private void pruneStaleOperations(int persisted, @NonNull Set<String> discoveredNames) {
        if (persisted <= 0 || discoveredNames.isEmpty()) {
            return;
        }
        int pruned = toolMetadataRepository.pruneDiscoveredOperationsExcept(discoveredNames);
        if (pruned > 0) {
            toolsPrunedTotal.increment(pruned);
            log.info("Pruned {} stale openapi mcp_tool row(s) not in the current spec (#1121)", pruned);
        }
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
        // Idempotent (re)registration so periodic refresh can re-add a tool without the server
        // rejecting it as already-registered: remove any existing tool of the same name first
        // (no-op / swallowed if absent), then add the current specification.
        return mcpAsyncServer
                .removeTool(toolName)
                .onErrorResume(ignored -> Mono.empty())
                .then(mcpAsyncServer.addTool(specification))
                .doOnSuccess(ignored -> {
                    toolsRegisteredTotal.increment();
                    log.info("Registered MCP tool {} in {} ms", toolName, elapsedMs(startNanos));
                });
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
