package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.discovery.OpenApiDocumentFetcher;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.internal.scopegraph.ScopeGraphHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.models.OpenAPI;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

/**
 * ADR-0069 section 4 (spec 2.6): every registration cycle ends by requesting a scope-graph rebuild.
 * The startup bootstrap and the discovery refresh both run {@code registerDiscoveredTools()}, so this
 * one hook is the trigger for all three.
 */
class ToolRegistrationScopeGraphTriggerTest {

    private static final URI GATEWAY = URI.create("http://gateway.test");

    private final OpenApiDocumentFetcher fetcher = mock(OpenApiDocumentFetcher.class);
    private final OpenApiToolMapper mapper = mock(OpenApiToolMapper.class);
    private final McpAsyncServer server = mock(McpAsyncServer.class);
    private final ScopeGraphHolder holder = mock(ScopeGraphHolder.class);

    @Test
    @DisplayName("a successful registration cycle requests one rebuild")
    void rebuildAfterSuccessfulRegistration() {
        McpServerFeatures.AsyncToolSpecification spec = toolSpec("workorder_getworkorder");
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                new OpenApiDocumentFetcher.DiscoveredOpenApi("aggregate", GATEWAY, new OpenAPI());
        when(fetcher.fetchAggregateSpec()).thenReturn(Mono.just(discovered));
        when(mapper.toAggregateToolSpecifications(GATEWAY, discovered.openApi()))
                .thenReturn(List.of(spec));
        when(server.removeTool(any())).thenReturn(Mono.empty());
        when(server.addTool(spec)).thenReturn(Mono.empty());
        when(server.notifyToolsListChanged()).thenReturn(Mono.empty());

        service(holder).registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(holder, times(1)).rebuild();
    }

    @Test
    @DisplayName("a cycle that fails still requests a rebuild: rows persisted before the failure are in the catalog")
    void rebuildAfterFailedRegistration() {
        when(fetcher.fetchAggregateSpec()).thenReturn(Mono.error(new IllegalStateException("gateway down")));

        service(holder).registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(holder, times(1)).rebuild();
    }

    @Test
    @DisplayName("a refresh cancelled by its timeout still requests one rebuild")
    void rebuildAfterCancelledRefresh() {
        when(fetcher.fetchAggregateSpec()).thenReturn(Mono.never());

        // DiscoveryRefreshScheduler blocks with a timeout, which cancels the cycle; the only trigger
        // left is doOnCancel.
        Mono<Void> cycle = service(holder).registerDiscoveredTools();
        assertThatCode(() -> cycle.block(Duration.ofMillis(100))).isInstanceOf(IllegalStateException.class);

        verify(holder, times(1)).rebuild();
    }

    @Test
    @DisplayName(
            "without a holder (the pre-ADR-0069 constructor, or none in the context) the cycle completes as before")
    void noHolderIsANoOp() {
        when(fetcher.fetchAggregateSpec()).thenReturn(Mono.error(new IllegalStateException("gateway down")));
        ToolRegistrationServiceImpl withoutProvider = new ToolRegistrationServiceImpl(
                properties(),
                fetcher,
                mapper,
                server,
                mock(ToolMetadataRepository.class),
                "http://api-gateway:8080",
                new SimpleMeterRegistry(),
                List.of());

        assertThatCode(() -> withoutProvider.registerDiscoveredTools().block(Duration.ofSeconds(5)))
                .doesNotThrowAnyException();
        assertThatCode(() -> service(null).registerDiscoveredTools().block(Duration.ofSeconds(5)))
                .doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private ToolRegistrationServiceImpl service(ScopeGraphHolder scopeGraphHolder) {
        ObjectProvider<ScopeGraphHolder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scopeGraphHolder);
        return new ToolRegistrationServiceImpl(
                properties(),
                fetcher,
                mapper,
                server,
                mock(ToolMetadataRepository.class),
                "http://api-gateway:8080",
                new SimpleMeterRegistry(),
                List.of(),
                provider);
    }

    private static McpServerProperties properties() {
        return new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                List.of(),
                List.of(),
                "http://gateway.test/v3/api-docs",
                List.of(),
                List.of(),
                Map.of());
    }

    private static McpServerFeatures.AsyncToolSpecification toolSpec(String name) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(name)
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build();
        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> Mono.empty())
                .build();
    }
}
