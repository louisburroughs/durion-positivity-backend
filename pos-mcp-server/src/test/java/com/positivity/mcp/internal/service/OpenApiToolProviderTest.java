package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.discovery.OperationProxyFactory;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.service.CurrentUserContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gate 3: provider gating + fail-closed behaviour. */
class OpenApiToolProviderTest {

    private final ToolMetadataRepository repository = mock(ToolMetadataRepository.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final OperationProxyFactory proxyFactory = mock(OperationProxyFactory.class);
    private final RequestScopedUserContext userContext = new RequestScopedUserContext();
    private final OpenApiToolProvider provider = new OpenApiToolProvider(
            repository, embeddingModel, userContext, proxyFactory, new ObjectMapper(), 8, Duration.ofSeconds(30));

    @AfterEach
    void cleanup() {
        userContext.clear();
    }

    private ToolProviderRequest request() {
        return new ToolProviderRequest("mem-1", UserMessage.from("show open workorders"));
    }

    @Test
    @DisplayName("no request-scoped context -> no tools (fail-closed)")
    void failClosedWithoutContext() {
        ToolProviderResult result = provider.provideTools(request());
        assertThat(result.tools()).isEmpty();
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
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[] {0.1f, 0.2f})));
        DiscoveredOperation executable = new DiscoveredOperation(
                "workorders_getallworkorders",
                "List workorders",
                "GET",
                "/v1/workorders/{workorderId}",
                "pos-workorder",
                "{\"query\":[{\"name\":\"status\",\"type\":\"string\",\"required\":true}]}");
        DiscoveredOperation notExecutable =
                new DiscoveredOperation("broken_op", "missing coords", null, null, null, null);
        lenient()
                .when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of(executable, notExecutable));

        ToolProviderResult result = provider.provideTools(request());

        // Only the executable op (with coordinates) is exposed.
        assertThat(result.tools()).hasSize(1);
        var spec = result.tools().keySet().iterator().next();
        assertThat(spec.name()).isEqualTo("workorders_getallworkorders");
        // Description surfaces method+path so the model knows the template; envelope gives the params.
        assertThat(spec.description()).contains("GET", "/v1/workorders");
        assertThat(spec.parameters()).isNotNull();
        assertThat(spec.parameters().properties()).containsKeys("pathParams", "queryParams", "headers", "body");
        // Path params are typed from the template: {workorderId} → a required string property.
        var pathParams = (JsonObjectSchema) spec.parameters().properties().get("pathParams");
        assertThat(pathParams.properties()).containsKey("workorderId");
        assertThat(pathParams.required()).contains("workorderId");
        // Query params are typed + required from the persisted input_schema JSON.
        var queryParams = (JsonObjectSchema) spec.parameters().properties().get("queryParams");
        assertThat(queryParams.properties()).containsKey("status");
        assertThat(queryParams.required()).contains("status");
        // Provider publishes the surfaced openapi tool names for telemetry (facade vs openapi source).
        assertThat(userContext.currentDiscoveredOpenapiToolNames()).containsExactly("workorders_getallworkorders");
    }
}
