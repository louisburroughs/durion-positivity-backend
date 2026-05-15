package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
                List.of());
        return new ToolRegistrationServiceImpl(
                properties, openApiDocumentFetcher, openApiToolMapper, mcpAsyncServer);
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(ToolRegistrationServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(ToolRegistrationServiceImpl.class);
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
