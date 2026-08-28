package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.discovery.OperationProxyFactory;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/** Gate 3: provider gating + fail-closed behaviour. */
class OpenApiToolProviderTest {

    private final ToolMetadataRepository repository = mock(ToolMetadataRepository.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final OperationProxyFactory proxyFactory = mock(OperationProxyFactory.class);
    private final RequestScopedUserContext userContext = new RequestScopedUserContext();
    private final OpenApiToolProvider provider = new OpenApiToolProvider(
            repository, embeddingModel, userContext, proxyFactory, new ObjectMapper(), 8, Duration.ofSeconds(30), null);

    @AfterEach
    void cleanup() {
        userContext.clear();
    }

    @Test
    @DisplayName("no request-scoped context -> no tools (fail-closed)")
    void failClosedWithoutContext() {
        assertThat(provider.resolveToolCallbacks("show open workorders")).isEmpty();
    }

    @Test
    @DisplayName("with context, exposes permission-gated executable discovered ops")
    void exposesGatedOps() {
        userContext.set(new CurrentUserContext(
                "advisor",
                UUID.randomUUID(),
                "ROLE_SERVICE_ADVISOR",
                Set.of("ROLE_SERVICE_ADVISOR"),
                Set.of("AUTHENTICATED"),
                Set.of("workorder:workorder:view")));
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        DiscoveredOperation executable = new DiscoveredOperation(
                "workorders_getallworkorders",
                "List workorders",
                "GET",
                "/v1/workorders/{workorderId}",
                "pos-workorder",
                "{\"query\":[{\"name\":\"status\",\"type\":\"string\",\"required\":true}]}",
                List.of());
        DiscoveredOperation notExecutable =
                new DiscoveredOperation("broken_op", "missing coords", null, null, null, null, List.of());
        lenient()
                .when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of(executable, notExecutable));

        var result = provider.resolveToolCallbacks("show open workorders");

        // Only the executable op (with coordinates) is exposed.
        assertThat(result).hasSize(1);
        var spec = result.getFirst().getToolDefinition();
        assertThat(spec.name()).isEqualTo("workorders_getallworkorders");
        // Description surfaces method+path so the model knows the template; envelope gives the params.
        assertThat(spec.description()).contains("GET", "/v1/workorders");
        assertThat(spec.inputSchema()).contains("\"pathParams\"", "\"queryParams\"", "\"headers\"", "\"body\"");
        assertThat(spec.inputSchema()).contains("\"workorderId\"", "\"status\"", "\"required\"");
        // Provider publishes the surfaced openapi tool names for telemetry (facade vs openapi source).
        assertThat(userContext.currentDiscoveredOpenapiToolNames()).containsExactly("workorders_getallworkorders");
    }

    @Test
    @DisplayName("#1193: records write-capability — non-GET discovered op sets the flag, GET-only does not")
    void recordsWriteCapableToolsPresent() {
        userContext.set(new CurrentUserContext(
                "advisor",
                UUID.randomUUID(),
                "ROLE_SERVICE_ADVISOR",
                Set.of("ROLE_SERVICE_ADVISOR"),
                Set.of("AUTHENTICATED"),
                Set.of("workorder:workorder:view")));
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        DiscoveredOperation readOp = new DiscoveredOperation(
                "workorders_getallworkorders",
                "List workorders",
                "GET",
                "/v1/workorders",
                "pos-workorder",
                null,
                List.of());
        DiscoveredOperation writeOp = new DiscoveredOperation(
                "workorders_createworkorder",
                "Create workorder",
                "POST",
                "/v1/workorders",
                "pos-workorder",
                null,
                List.of());

        when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of(readOp));
        provider.resolveToolCallbacks("show open workorders");
        assertThat(userContext.currentWriteCapableToolsPresent()).isFalse();

        when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of(readOp, writeOp));
        provider.resolveToolCallbacks("create a workorder");
        assertThat(userContext.currentWriteCapableToolsPresent()).isTrue();
    }

    @Test
    @DisplayName("#779: shared provider does not leak a high-permission tool to a low-permission caller")
    void doesNotLeakAcrossCallersViaSharedProvider() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        DiscoveredOperation highPermOp = new DiscoveredOperation(
                "accounting_listinvoices",
                "List invoices",
                "GET",
                "/v1/accounting/invoices",
                "pos-accounting",
                null,
                List.of());
        // Repo gates on the caller's permission codes: the tool is returned only when the caller
        // actually holds accounting:invoice:view (mirrors the fail-closed SQL filter).
        when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenAnswer(inv -> {
                    Set<String> permissionCodes = inv.getArgument(2);
                    return permissionCodes.contains("accounting:invoice:view") ? List.of(highPermOp) : List.of();
                });

        // High-permission caller sees the discovered tool.
        userContext.set(callerWithPermissions("accountant", Set.of("AUTHENTICATED", "accounting:invoice:view")));
        assertThat(provider.resolveToolCallbacks("show invoices"))
                .extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("accounting_listinvoices");
        userContext.clear();

        // Same provider instance (as held by a shared cached agent), a low-permission caller: no leak.
        userContext.set(callerWithPermissions("cashier", Set.of("AUTHENTICATED")));
        assertThat(provider.resolveToolCallbacks("show invoices")).isEmpty();
    }

    private static CurrentUserContext callerWithPermissions(String username, Set<String> permissionCodes) {
        return new CurrentUserContext(
                username, UUID.randomUUID(), "ROLE_X", Set.of("ROLE_X"), Set.of("AUTHENTICATED"), permissionCodes);
    }
}
