package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.discovery.OpenApiDocumentFetcher;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.models.OpenAPI;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class ToolRegistrationServiceImplTest {

    @Mock
    private OpenApiDocumentFetcher openApiDocumentFetcher;

    @Mock
    private OpenApiToolMapper openApiToolMapper;

    @Mock
    private McpAsyncServer mcpAsyncServer;

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
        when(mcpAsyncServer.addTool(spec)).thenReturn(Mono.empty());
        when(mcpAsyncServer.notifyToolsListChanged()).thenReturn(Mono.empty());

        ToolRegistrationServiceImpl service = serviceUnderTest();
        service.registerDiscoveredTools().block(Duration.ofSeconds(5));

        verify(openApiDocumentFetcher).fetchAggregateSpec();
        verify(openApiToolMapper).toAggregateToolSpecifications(GATEWAY_BASE_URI, discovered.openApi());
        verify(mcpAsyncServer).addTool(spec);
        verify(mcpAsyncServer).notifyToolsListChanged();
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

    // --- helpers ---

    private ToolRegistrationServiceImpl serviceUnderTest() {
        McpServerProperties properties = new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                List.of(),
                List.of(),
                "http://gateway.test/v3/api-docs",
                List.of());
        return new ToolRegistrationServiceImpl(
                properties, openApiDocumentFetcher, openApiToolMapper, mcpAsyncServer);
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
