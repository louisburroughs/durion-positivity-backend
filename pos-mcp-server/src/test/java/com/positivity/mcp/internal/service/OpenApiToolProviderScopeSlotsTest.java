package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.discovery.OperationProxyFactory;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;

/**
 * ADR-0069 §6, discovered slots: appended after the ANN cut, sharing the facade step's cap, admitted
 * by the SQL gate alone, with write-capability recomputed over the union.
 */
class OpenApiToolProviderScopeSlotsTest {

    /** Seeds workorder (HIGH): the scope's discovered tools are getworkorder, listworkorders and, with the code, createworkorder. */
    private static final String MESSAGE = "is work order WO-20391 approved?";

    private static final Set<String> CALLER = Set.of(
            "AUTHENTICATED",
            ScopeResolverFixtures.WORKORDER_VIEW,
            ScopeResolverFixtures.WORKORDER_LIST,
            ScopeResolverFixtures.WORKORDER_CREATE);

    private final ToolMetadataRepository repository = mock(ToolMetadataRepository.class);
    private final org.springframework.ai.embedding.EmbeddingModel embeddingModel =
            mock(org.springframework.ai.embedding.EmbeddingModel.class);
    private final RequestScopedUserContext userContext = new RequestScopedUserContext();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OpenApiToolProvider provider = new OpenApiToolProvider(
            repository,
            embeddingModel,
            userContext,
            mock(OperationProxyFactory.class),
            new ObjectMapper(),
            8,
            Duration.ofSeconds(30),
            null);

    @BeforeEach
    void caller() {
        userContext.set(new CurrentUserContext(
                "advisor", UUID.randomUUID(), "ROLE_SERVICE_ADVISOR", Set.of("ROLE_SERVICE_ADVISOR"), CALLER, CALLER));
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
    }

    @AfterEach
    void cleanup() {
        userContext.clear();
    }

    private void enforceTools(int slots, String message) {
        ScopeGraphProperties properties = ScopeResolverFixtures.enforce(60, slots, 0, Consumer.TOOLS);
        provider.setScopeConsumers(ScopeResolverFixtures.consumers(properties, meters));
        ScopeSet scope =
                ScopeResolverFixtures.resolver(properties, meters).resolve(message, CALLER, WorkflowState.IDLE);
        userContext.recordScope(scope);
    }

    private void annCut(DiscoveredOperation... ops) {
        when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), anySet(), anyString()))
                .thenReturn(List.of(ops));
    }

    /** The gate answers by name, in name order, like the SQL. */
    private void gateAdmits(String... names) {
        List<String> admitted = List.of(names);
        when(repository.findDiscoveredByNamesForPermissions(anyCollection(), anySet(), anyString()))
                .thenAnswer(invocation -> {
                    Collection<String> asked = invocation.getArgument(0);
                    return admitted.stream()
                            .filter(asked::contains)
                            .sorted()
                            .map(OpenApiToolProviderScopeSlotsTest::op)
                            .toList();
                });
    }

    @Test
    @DisplayName("scope operations are appended after the ANN cut in slot order; the cut is untouched")
    void appendsAfterTheAnnCut() {
        enforceTools(8, MESSAGE);
        annCut(op("workorder_listworkorders"));
        gateAdmits("workorder_getworkorder", "workorder_createworkorder");

        List<ToolCallback> tools = provider.resolveToolCallbacks(MESSAGE);

        // getworkorder (hop 1, reads) before createworkorder (hop 1, writes); listworkorders was ranked.
        assertThat(names(tools))
                .containsExactly("workorder_listworkorders", "workorder_getworkorder", "workorder_createworkorder");
        assertThat(userContext.currentScopeAddedToolNames())
                .containsExactly("workorder_getworkorder", "workorder_createworkorder");
        assertThat(userContext.currentDiscoveredOpenapiToolNames()).isEqualTo(names(tools));
        // The POST the scope added makes the request write-capable although the ANN cut was read-only.
        assertThat(userContext.currentWriteCapableToolsPresent()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(repository).findDiscoveredByNamesForPermissions(asked.capture(), eq(CALLER), eq("IDLE"));
        assertThat(asked.getValue()).containsExactly("workorder_getworkorder", "workorder_createworkorder");
    }

    @Test
    @DisplayName("facade and discovered additions share one cap: what the facades used is gone")
    void sharesTheCapWithTheFacadeStep() {
        enforceTools(3, MESSAGE);
        // The selection engine already added two facades this turn.
        userContext.recordScopeAddedTools(List.of("WorkorderFacadeTool", "InvoiceFacadeTool"));
        annCut();
        gateAdmits("workorder_getworkorder", "workorder_createworkorder", "workorder_listworkorders");

        List<ToolCallback> tools = provider.resolveToolCallbacks(MESSAGE);

        assertThat(names(tools)).containsExactly("workorder_getworkorder");
        assertThat(userContext.currentScopeAddedToolNames())
                .containsExactly("WorkorderFacadeTool", "InvoiceFacadeTool", "workorder_getworkorder");
        assertThat(userContext.currentWriteCapableToolsPresent()).isFalse();

        // Cap exhausted by the facades: no query at all.
        userContext.recordScopeAddedTools(List.of("x"));
        provider.resolveToolCallbacks(MESSAGE);
        verify(repository, org.mockito.Mockito.times(1))
                .findDiscoveredByNamesForPermissions(anyCollection(), anySet(), anyString());
    }

    @Test
    @DisplayName("the SQL gate decides: a forged scope tool the gate rejects is neither exposed nor counted")
    void sqlGateDecides() {
        enforceTools(8, MESSAGE);
        annCut();
        // The gate knows nothing of createworkorder for this caller.
        gateAdmits("workorder_getworkorder", "workorder_listworkorders");

        List<ToolCallback> tools = provider.resolveToolCallbacks(MESSAGE);

        assertThat(names(tools)).containsExactly("workorder_getworkorder", "workorder_listworkorders");
        assertThat(userContext.currentScopeAddedToolNames())
                .containsExactly("workorder_getworkorder", "workorder_listworkorders");
        assertThat(userContext.currentWriteCapableToolsPresent()).isFalse();
    }

    @Test
    @DisplayName("nothing is added without the tools consumer, without a scope, or on a NONE scope")
    void nothingWhenInactive() {
        annCut(op("workorder_listworkorders"));

        // No switch wired at all.
        assertThat(names(provider.resolveToolCallbacks(MESSAGE))).containsExactly("workorder_listworkorders");

        // Enforced, but no scope published for the request.
        provider.setScopeConsumers(
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.enforce(60, Consumer.TOOLS), meters));
        assertThat(names(provider.resolveToolCallbacks(MESSAGE))).containsExactly("workorder_listworkorders");

        // Enforced, NONE scope (warm-up shape).
        enforceTools(8, "ROLE_SERVICE_ADVISOR");
        assertThat(userContext.currentScope().orElseThrow().confidence()).isEqualTo(ScopeSet.Confidence.NONE);
        assertThat(names(provider.resolveToolCallbacks(MESSAGE))).containsExactly("workorder_listworkorders");

        // Shadow, HIGH scope.
        ScopeGraphProperties shadow = ScopeResolverFixtures.shadow(60);
        provider.setScopeConsumers(ScopeResolverFixtures.consumers(shadow, meters));
        userContext.recordScope(
                ScopeResolverFixtures.resolver(shadow, meters).resolve(MESSAGE, CALLER, WorkflowState.IDLE));
        assertThat(names(provider.resolveToolCallbacks(MESSAGE))).containsExactly("workorder_listworkorders");

        assertThat(userContext.currentScopeAddedToolNames()).isEmpty();
        verify(repository, never()).findDiscoveredByNamesForPermissions(anyCollection(), anySet(), anyString());
    }

    private static DiscoveredOperation op(String name) {
        return new DiscoveredOperation(
                name,
                name + " description",
                name.contains("create") ? "POST" : "GET",
                "/v1/workorders",
                "pos-workorder",
                null,
                List.of());
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(tool -> tool.getToolDefinition().name()).toList();
    }
}
