package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.discovery.OpenApiDocumentFetcher;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.models.OpenAPI;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class ToolRegistrationServiceImplTest {

    @Mock
    private OpenApiDocumentFetcher openApiDocumentFetcher;

    @Mock
    private OpenApiToolMapper openApiToolMapper;

    @Mock
    private McpAsyncServer mcpAsyncServer;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static final URI GATEWAY_BASE_URI = URI.create("http://gateway.test");

    @Test
    @DisplayName("registerDiscoveredTools fetches aggregate spec and registers tools via addTool")
    void registerDiscoveredTools_fetchesAggregateSpec_andRegistersTools() {
        McpServerFeatures.AsyncToolSpecification spec = toolSpec("accounting_listinvoices");
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("aggregate", GATEWAY_BASE_URI, new OpenAPI());

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        when(openApiToolMapper.toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi()))
                .thenReturn(List.of(spec));
        when(mcpAsyncServer.removeTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.addTool(spec)).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        ToolRegistrationServiceImpl service = serviceUnderTest();
        service.registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(openApiDocumentFetcher).fetchAggregateSpec();
        verify(openApiToolMapper).toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi());
        verify(mcpAsyncServer).addTool(spec);
        verify(mcpAsyncServer).notifyToolsListChanged();
        // #645: discovery/registration counters increment (Prometheus: tools_discovered_total /
        // tools_registered_total).
        assertThat(meterRegistry.get("tools.discovered").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("tools.registered").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("registerDiscoveredTools does not register tools and returns empty when aggregate fetch returns empty")
    void registerDiscoveredTools_skipsRegistration_whenAggregateFetchReturnsEmpty() {
        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.empty());

        ToolRegistrationServiceImpl service = serviceUnderTest();
        Void result = service.registerDiscoveredTools().block(Duration.ofSeconds(5));

        assertThat(result).isNull();
        verify(openApiDocumentFetcher).fetchAggregateSpec();
        verify(openApiToolMapper, never()).toAggregateToolSpecifications(any(), any());
        verify(mcpAsyncServer, never()).addTool(any());
    }

    @Test
    @DisplayName("registerDiscoveredTools skips registration and completes normally when aggregate fetch errors")
    void registerDiscoveredTools_completesNormally_whenAggregateFetchErrors() {
        when(openApiDocumentFetcher.fetchAggregateSpec())
                .thenReturn(Mono.error(new RuntimeException("connection refused")));

        ToolRegistrationServiceImpl service = serviceUnderTest();
        Void result = service.registerDiscoveredTools().block(Duration.ofSeconds(5));

        assertThat(result).isNull();
        verify(mcpAsyncServer, never()).addTool(any());
        verify(mcpAsyncServer, never()).notifyToolsListChanged();
    }

    @Test
    @DisplayName("registerDiscoveredTools returns empty without calling addTool when mapper returns no specs")
    void registerDiscoveredTools_skipsAddTool_whenMapperReturnsNoSpecs() {
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("aggregate", GATEWAY_BASE_URI, new OpenAPI());

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        when(openApiToolMapper.toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi()))
                .thenReturn(List.of());

        ToolRegistrationServiceImpl service = serviceUnderTest();
        service.registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(mcpAsyncServer, never()).addTool(any());
        verify(mcpAsyncServer, never()).notifyToolsListChanged();
    }

    @Test
    @DisplayName("registerDiscoveredTools logs a WARN when included-services is configured in aggregate-first mode")
    void registerDiscoveredTools_logsWarning_whenIncludedServicesConfiguredInAggregateFirstMode() {
        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.empty());

        ListAppender<ILoggingEvent> logAppender = attachLogAppender();
        try {
            ToolRegistrationServiceImpl service = serviceWithIncludedServices(List.of("event-receiver"));
            service.registerDiscoveredTools().block(Duration.ofSeconds(5));

            boolean hasDeprecationWarning = logAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .anyMatch(e -> e.getFormattedMessage().contains("included-services"));
            assertThat(hasDeprecationWarning)
                    .as("Expected a WARN log mentioning 'included-services' when it is set in aggregate-first mode")
                    .isTrue();
        } finally {
            detachLogAppender(logAppender);
        }
    }

    @Test
    @DisplayName("registerDiscoveredTools falls back to per-service Eureka discovery when aggregate is empty (#645)")
    void registerDiscoveredTools_fallsBackToPerServiceDiscovery_whenAggregateEmpty() {
        URI serviceBase = URI.create("http://pos-order.test");
        OpenAPI serviceApi = new OpenAPI();
        OpenApiDocumentFetcher.DiscoveredOpenApi serviceDoc =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("pos-order", serviceBase, serviceApi);
        McpServerFeatures.AsyncToolSpecification spec = toolSpec("pos-order_getorder");

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.empty());
        when(openApiDocumentFetcher.fallbackServiceIds()).thenReturn(List.of("pos-order"));
        when(openApiDocumentFetcher.fetchForService("pos-order")).thenReturn(Mono.just(serviceDoc));
        when(openApiToolMapper.toToolSpecifications("pos-order", serviceBase, serviceApi))
                .thenReturn(List.of(spec));
        when(mcpAsyncServer.removeTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.addTool(spec)).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        serviceUnderTest().registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(openApiDocumentFetcher).fetchForService("pos-order");
        verify(mcpAsyncServer).addTool(spec);
        verify(mcpAsyncServer).notifyToolsListChanged();
        assertThat(meterRegistry.get("tools.discovered").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("tools.registered").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("registerDiscoveredTools does not use the per-service fallback when the aggregate registers tools")
    void registerDiscoveredTools_skipsFallback_whenAggregateRegistersTools() {
        McpServerFeatures.AsyncToolSpecification spec = toolSpec("accounting_listinvoices");
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("aggregate", GATEWAY_BASE_URI, new OpenAPI());

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        when(openApiToolMapper.toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi()))
                .thenReturn(List.of(spec));
        when(mcpAsyncServer.removeTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.addTool(spec)).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        serviceUnderTest().registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(openApiDocumentFetcher, never()).fallbackServiceIds();
        verify(openApiDocumentFetcher, never()).fetchForService(any());
    }

    @Test
    @DisplayName("registerDiscoveredTools registers nothing when both aggregate and per-service fallback are empty")
    void registerDiscoveredTools_registersNothing_whenAggregateAndFallbackEmpty() {
        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.empty());
        when(openApiDocumentFetcher.fallbackServiceIds()).thenReturn(List.of());

        serviceUnderTest().registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(openApiDocumentFetcher, never()).fetchForService(any());
        verify(mcpAsyncServer, never()).addTool(any());
        verify(mcpAsyncServer, never()).notifyToolsListChanged();
    }

    @Test
    @DisplayName("registerDiscoveredTools prunes stale openapi rows absent from the current spec after persisting "
            + "(#1121)")
    void registerDiscoveredTools_prunesOrphans_afterPersistingAggregate() {
        McpServerFeatures.AsyncToolSpecification spec = toolSpec("accounting_listinvoices");
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("aggregate", GATEWAY_BASE_URI, new OpenAPI());
        DiscoveredOperation op = new DiscoveredOperation(
                "accounting_listinvoices",
                "List invoices",
                "GET",
                "/accounting/v1/invoices",
                "http://api-gateway:8080",
                null,
                List.of());

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        when(openApiToolMapper.toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi()))
                .thenReturn(List.of(spec));
        when(openApiToolMapper.toDiscoveredOperations("http://api-gateway:8080", discovered.openApi()))
                .thenReturn(List.of(op));
        when(mcpAsyncServer.removeTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.addTool(spec)).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        ToolMetadataRepository repo = mock(ToolMetadataRepository.class);
        when(repo.upsertDiscoveredOperation(any(), any()))
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(repo.pruneDiscoveredOperationsExcept(any(), any())).thenReturn(3);

        serviceUnderTest(repo).registerDiscoveredTools().block(Duration.ofSeconds(5));

        // Reconciliation runs with exactly the names discovered this run, and the counter reflects the deletes.
        verify(repo).pruneDiscoveredOperationsExcept(Set.of("accounting_listinvoices"), Set.of());
        assertThat(meterRegistry.get("tools.pruned").counter().count()).isEqualTo(3.0);
    }

    /**
     * Covers the {@code persisted <= 0} arm of {@code pruneStaleOperations}'s guard. The sibling arm —
     * {@code persisted > 0 && discoveredNames.isEmpty()} — is not exercised anywhere: {@code
     * discoveredNames} is derived from the same operations list {@code persistAll} iterates, and {@link
     * DiscoveredOperation#name()} is {@code @NonNull} by contract, so a successful persist (persisted >
     * 0) always implies at least one discovered name. That combination is an unreachable defensive
     * guard, not a real branch to characterise.
     */
    @Test
    @DisplayName("registerDiscoveredTools runs the prune with the failed domain excluded when a per-service "
            + "spec fetch failed (#1632)")
    void registerDiscoveredTools_prunesWithFailedDomainExcluded_whenAnyServiceFetchFailed() {
        // A partial aggregate: this cycle persisted real ops, but /workorder's spec fetch failed.
        // Pruning workorder rows would treat every workorder_* op as removed and delete it — exactly
        // what happened on alpha (2026-09-01) after a transient routing fault. The prune must still
        // RUN (so healthy domains reconcile every cycle) but with the failed prefix's domain excluded.
        McpServerFeatures.AsyncToolSpecification spec = toolSpec("accounting_listinvoices");
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered = new OpenApiDocumentFetcher.DiscoveredOpenApi(
                "aggregate", GATEWAY_BASE_URI, new OpenAPI(), List.of("/workorder"));
        DiscoveredOperation op = new DiscoveredOperation(
                "accounting_listinvoices",
                "List invoices",
                "GET",
                "/accounting/v1/invoices",
                "http://api-gateway:8080",
                null,
                List.of());

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        // The #1632 targeted fallback retries the failed prefix per-service; an empty result here
        // simulates the service still being unreachable (fail-soft, logged, no registration).
        when(openApiDocumentFetcher.fetchForService("workorder")).thenReturn(Mono.empty());
        when(openApiToolMapper.toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi()))
                .thenReturn(List.of(spec));
        when(openApiToolMapper.toDiscoveredOperations("http://api-gateway:8080", discovered.openApi()))
                .thenReturn(List.of(op));
        when(mcpAsyncServer.removeTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.addTool(spec)).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        ToolMetadataRepository repo = mock(ToolMetadataRepository.class);
        when(repo.upsertDiscoveredOperation(any(), any()))
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        ListAppender<ILoggingEvent> logAppender = attachLogAppender();
        try {
            serviceUnderTest(repo).registerDiscoveredTools().block(Duration.ofSeconds(5));

            // #1632 companion path: when the targeted retry ALSO fails, the miss is WARN-logged with
            // the service id so operators know the domain's tools stay absent until a refresh.
            boolean hasFallbackMissWarning = logAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .anyMatch(e -> e.getFormattedMessage().contains("Targeted per-service fallback also failed")
                            && e.getFormattedMessage().contains("workorder"));
            assertThat(hasFallbackMissWarning)
                    .as("Expected a WARN naming 'workorder' when the targeted fallback fetch is also empty")
                    .isTrue();
        } finally {
            detachLogAppender(logAppender);
        }

        // Ops still persist (upsert-only is safe on a partial view), the targeted fallback retried the
        // failed prefix, and the prune ran with the failed domain excluded rather than being skipped.
        verify(repo).upsertDiscoveredOperation(any(), any());
        verify(openApiDocumentFetcher).fetchForService("workorder");
        verify(repo).pruneDiscoveredOperationsExcept(Set.of("accounting_listinvoices"), Set.of("workorder"));
        // Everything else still registers, but no workorder tool reaches the live surface: the only
        // addTool this cycle is the healthy aggregate spec.
        verify(mcpAsyncServer).addTool(spec);
        verify(mcpAsyncServer, times(1)).addTool(any());
        // The partial-discovery counter is alertable (#1632): one failed prefix → +1.
        assertThat(meterRegistry.get("discovery.partial").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("registerDiscoveredTools restores a failed prefix's tools to the LIVE tool surface via the "
            + "targeted per-service fallback in the same cycle (#1632)")
    void registerDiscoveredTools_restoresFailedPrefixToolsToLiveSurface_viaTargetedFallback() {
        // Same partial-aggregate shape as the prune test above, but here the failed service IS
        // reachable through the per-service Eureka path. Keeping the DB rows alone is not enough —
        // McpAsyncServer's tool list is in-memory — so the fallback must addTool() the workorder
        // tools in this cycle, not merely preserve them for a future one.
        McpServerFeatures.AsyncToolSpecification aggregateSpec = toolSpec("accounting_listinvoices");
        McpServerFeatures.AsyncToolSpecification workorderSpec = toolSpec("workorder_getworkorder");
        URI workorderBase = URI.create("http://pos-workorder.test");
        OpenAPI workorderApi = new OpenAPI();
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered = new OpenApiDocumentFetcher.DiscoveredOpenApi(
                "aggregate", GATEWAY_BASE_URI, new OpenAPI(), List.of("/workorder"));
        OpenApiDocumentFetcher.DiscoveredOpenApi workorderDoc =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("workorder", workorderBase, workorderApi);

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        // The "/workorder" prefix maps to service id "workorder", mirroring swagger-config doc URLs.
        when(openApiDocumentFetcher.fetchForService("workorder")).thenReturn(Mono.just(workorderDoc));
        when(openApiToolMapper.toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi()))
                .thenReturn(List.of(aggregateSpec));
        when(openApiToolMapper.toToolSpecifications("workorder", workorderBase, workorderApi))
                .thenReturn(List.of(workorderSpec));
        when(openApiToolMapper.toDiscoveredOperations("http://api-gateway:8080", discovered.openApi()))
                .thenReturn(List.of());
        when(mcpAsyncServer.removeTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.addTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        serviceUnderTest().registerDiscoveredTools().block(Duration.ofSeconds(5));

        // The failed domain's tool reaches tools/list in the SAME cycle: both the aggregate tool and
        // the fallback-recovered workorder tool are added, and clients are notified after each batch.
        verify(mcpAsyncServer).addTool(aggregateSpec);
        verify(mcpAsyncServer).addTool(workorderSpec);
        verify(mcpAsyncServer, times(2)).notifyToolsListChanged();
        assertThat(meterRegistry.get("tools.discovered").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("tools.registered").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("discovery.partial").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("registerDiscoveredTools does not prune when the aggregate persists no ops (#1121 safety guard)")
    void registerDiscoveredTools_doesNotPrune_whenNothingPersisted() {
        McpServerFeatures.AsyncToolSpecification spec = toolSpec("accounting_listinvoices");
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("aggregate", GATEWAY_BASE_URI, new OpenAPI());

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        when(openApiToolMapper.toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi()))
                .thenReturn(List.of(spec));
        // No discovered operations persisted this run → the guard must skip pruning so a bad/empty run
        // can never wipe the catalog.
        when(openApiToolMapper.toDiscoveredOperations("http://api-gateway:8080", discovered.openApi()))
                .thenReturn(List.of());
        when(mcpAsyncServer.removeTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.addTool(spec)).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        ToolMetadataRepository repo = mock(ToolMetadataRepository.class);
        serviceUnderTest(repo).registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(repo, never()).pruneDiscoveredOperationsExcept(any(), any());
    }

    @Test
    @DisplayName("registerDiscoveredTools skips persisting a discovered op with a null httpPath but still "
            + "counts its name toward the prune keep-set (#1121)")
    void registerDiscoveredTools_skipsUnmappedOp_butKeepsItsNameInPruneSet() {
        McpServerFeatures.AsyncToolSpecification spec = toolSpec("accounting_listinvoices");
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("aggregate", GATEWAY_BASE_URI, new OpenAPI());
        DiscoveredOperation mapped = new DiscoveredOperation(
                "accounting_listinvoices",
                "List invoices",
                "GET",
                "/accounting/v1/invoices",
                "http://api-gateway:8080",
                null,
                List.of());
        // No httpPath: the mapper produced a name/description but no execution path (e.g. an operation
        // the OpenAPI spec never gave a concrete route). persistDiscoveredOperations must skip the
        // repository upsert for it while still counting its name for the #1121 prune keep-set, since
        // discoveredNames is built from every discovered op's name, not just the persisted ones.
        DiscoveredOperation unmapped =
                new DiscoveredOperation("accounting_orphan", "Orphan op", null, null, null, null, List.of());

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        when(openApiToolMapper.toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi()))
                .thenReturn(List.of(spec));
        when(openApiToolMapper.toDiscoveredOperations("http://api-gateway:8080", discovered.openApi()))
                .thenReturn(List.of(mapped, unmapped));
        when(mcpAsyncServer.removeTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.addTool(spec)).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        ToolMetadataRepository repo = mock(ToolMetadataRepository.class);
        when(repo.upsertDiscoveredOperation(eq(mapped), any()))
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        serviceUnderTest(repo).registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(repo, never()).upsertDiscoveredOperation(eq(unmapped), any());
        verify(repo).upsertDiscoveredOperation(eq(mapped), any());
        verify(repo).pruneDiscoveredOperationsExcept(Set.of("accounting_listinvoices", "accounting_orphan"), Set.of());
    }

    @Test
    @DisplayName("registerDiscoveredTools logs and continues when upsertDiscoveredOperation fails for one op, "
            + "still persisting the rest")
    void registerDiscoveredTools_logsAndContinues_whenUpsertFailsForOneOp() {
        McpServerFeatures.AsyncToolSpecification spec = toolSpec("accounting_listinvoices");
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("aggregate", GATEWAY_BASE_URI, new OpenAPI());
        DiscoveredOperation ok = new DiscoveredOperation(
                "accounting_listinvoices",
                "List invoices",
                "GET",
                "/accounting/v1/invoices",
                "http://api-gateway:8080",
                null,
                List.of());
        DiscoveredOperation failing = new DiscoveredOperation(
                "accounting_broken",
                "Broken op",
                "GET",
                "/accounting/v1/broken",
                "http://api-gateway:8080",
                null,
                List.of());

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        when(openApiToolMapper.toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi()))
                .thenReturn(List.of(spec));
        when(openApiToolMapper.toDiscoveredOperations("http://api-gateway:8080", discovered.openApi()))
                .thenReturn(List.of(ok, failing));
        when(mcpAsyncServer.removeTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.addTool(spec)).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        ToolMetadataRepository repo = mock(ToolMetadataRepository.class);
        when(repo.upsertDiscoveredOperation(eq(ok), any()))
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        when(repo.upsertDiscoveredOperation(eq(failing), any())).thenThrow(new RuntimeException("db unavailable"));

        ListAppender<ILoggingEvent> logAppender = attachLogAppender();
        try {
            serviceUnderTest(repo).registerDiscoveredTools().block(Duration.ofSeconds(5));

            boolean hasFailureWarning = logAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .anyMatch(e -> e.getFormattedMessage().contains("accounting_broken"));
            assertThat(hasFailureWarning)
                    .as("Expected a WARN log naming the op that failed to persist")
                    .isTrue();
        } finally {
            detachLogAppender(logAppender);
        }
        // The failing op never reaches linkToolToWorkflow; the ok op still does (persistence continues
        // past the failure rather than aborting the batch).
        verify(repo).linkToolToWorkflow(eq(UUID.fromString("00000000-0000-0000-0000-000000000003")), any());
        verify(repo).pruneDiscoveredOperationsExcept(Set.of("accounting_listinvoices", "accounting_broken"), Set.of());
    }

    @Test
    @DisplayName("registerDiscoveredTools grants every x-required-permissions code from a discovered op (#781)")
    void registerDiscoveredTools_grantsEachRequiredPermission() {
        McpServerFeatures.AsyncToolSpecification spec = toolSpec("accounting_listinvoices");
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("aggregate", GATEWAY_BASE_URI, new OpenAPI());
        DiscoveredOperation op = new DiscoveredOperation(
                "accounting_listinvoices",
                "List invoices",
                "GET",
                "/accounting/v1/invoices",
                "http://api-gateway:8080",
                null,
                List.of("accounting:invoice:view", "accounting:invoice:export"));

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        when(openApiToolMapper.toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi()))
                .thenReturn(List.of(spec));
        when(openApiToolMapper.toDiscoveredOperations("http://api-gateway:8080", discovered.openApi()))
                .thenReturn(List.of(op));
        when(mcpAsyncServer.removeTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.addTool(spec)).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        ToolMetadataRepository repo = mock(ToolMetadataRepository.class);
        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        when(repo.upsertDiscoveredOperation(eq(op), any())).thenReturn(toolId);

        serviceUnderTest(repo).registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(repo).addToolPermission(toolId, "accounting:invoice:view");
        verify(repo).addToolPermission(toolId, "accounting:invoice:export");
    }

    @Test
    @DisplayName("registerDiscoveredTools does not increment the pruned counter when nothing was pruned (#1121)")
    void registerDiscoveredTools_doesNotIncrementPrunedCounter_whenPruneRemovesNothing() {
        McpServerFeatures.AsyncToolSpecification spec = toolSpec("accounting_listinvoices");
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("aggregate", GATEWAY_BASE_URI, new OpenAPI());
        DiscoveredOperation op = new DiscoveredOperation(
                "accounting_listinvoices",
                "List invoices",
                "GET",
                "/accounting/v1/invoices",
                "http://api-gateway:8080",
                null,
                List.of());

        when(openApiDocumentFetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        when(openApiToolMapper.toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi()))
                .thenReturn(List.of(spec));
        when(openApiToolMapper.toDiscoveredOperations("http://api-gateway:8080", discovered.openApi()))
                .thenReturn(List.of(op));
        when(mcpAsyncServer.removeTool(any())).thenReturn(Mono.empty());
        when(mcpAsyncServer.addTool(spec)).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        ToolMetadataRepository repo = mock(ToolMetadataRepository.class);
        when(repo.upsertDiscoveredOperation(any(), any()))
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000005"));
        // Current spec's op set matches the DB already, so the #1121 reconciliation deletes nothing.
        when(repo.pruneDiscoveredOperationsExcept(any(), any())).thenReturn(0);

        serviceUnderTest(repo).registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(repo).pruneDiscoveredOperationsExcept(Set.of("accounting_listinvoices"), Set.of());
        assertThat(meterRegistry.get("tools.pruned").counter().count()).isEqualTo(0.0);
    }

    // --- helpers ---

    private ToolRegistrationServiceImpl serviceUnderTest() {
        return serviceUnderTest(mock(ToolMetadataRepository.class));
    }

    private ToolRegistrationServiceImpl serviceUnderTest(ToolMetadataRepository toolMetadataRepository) {
        McpServerProperties properties = new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                List.of(),
                List.of(),
                "http://gateway.test/v3/api-docs",
                List.of(),
                Map.of());
        return new ToolRegistrationServiceImpl(
                properties,
                openApiDocumentFetcher,
                openApiToolMapper,
                mcpAsyncServer,
                toolMetadataRepository,
                "http://api-gateway:8080",
                meterRegistry);
    }

    private ToolRegistrationServiceImpl serviceWithIncludedServices(List<String> includedServices) {
        McpServerProperties properties = new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                includedServices,
                List.of(),
                "http://gateway.test/v3/api-docs",
                List.of(),
                Map.of());
        return new ToolRegistrationServiceImpl(
                properties,
                openApiDocumentFetcher,
                openApiToolMapper,
                mcpAsyncServer,
                mock(ToolMetadataRepository.class),
                "http://api-gateway:8080",
                meterRegistry);
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ToolRegistrationServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ToolRegistrationServiceImpl.class);
        logger.detachAppender(appender);
    }

    private static McpServerFeatures.AsyncToolSpecification toolSpec(String name) {
        var tool = McpSchema.Tool.builder()
                .name(name)
                .description("test tool")
                .inputSchema(new McpSchema.JsonSchema("object", null, null, null, null, null))
                .build();
        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> Mono.empty())
                .build();
    }
}
