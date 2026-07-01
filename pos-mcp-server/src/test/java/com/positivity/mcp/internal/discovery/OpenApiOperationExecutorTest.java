package com.positivity.mcp.internal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

class OpenApiOperationExecutorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    @DisplayName("execute routes via the gateway base URI (service_id), not the load balancer, and renders text")
    void execute_routesViaGatewayBaseUri() {
        OperationProxyFactory proxyFactory = mock(OperationProxyFactory.class);
        McpSchema.CallToolResult ok = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("[]")))
                .build();
        when(proxyFactory.handlerForBaseUri(any(URI.class), eq(HttpMethod.GET), eq("/v1/accounting/invoices")))
                .thenReturn((exchange, request) -> Mono.just(ok));

        DiscoveredOperation operation = new DiscoveredOperation(
                "accounting_listinvoices",
                "List invoices",
                "GET",
                "/v1/accounting/invoices",
                "http://api-gateway:8080",
                null);
        OpenApiOperationExecutor executor =
                new OpenApiOperationExecutor(proxyFactory, operation, new ObjectMapper(), TIMEOUT, null);

        String result = executor.execute(
                ToolExecutionRequest.builder()
                        .name("accounting_listinvoices")
                        .arguments("{}")
                        .build(),
                null);

        assertThat(result).isEqualTo("[]");
        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(proxyFactory).handlerForBaseUri(uriCaptor.capture(), eq(HttpMethod.GET), eq("/v1/accounting/invoices"));
        assertThat(uriCaptor.getValue()).isEqualTo(URI.create("http://api-gateway:8080"));
    }

    @Test
    @DisplayName("execute relays the caller's Authorization header into the outbound call")
    void execute_relaysAuthorizationHeader() {
        OperationProxyFactory proxyFactory = mock(OperationProxyFactory.class);
        McpSchema.CallToolResult ok = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("[]")))
                .build();
        AtomicReference<McpSchema.CallToolRequest> captured = new AtomicReference<>();
        when(proxyFactory.handlerForBaseUri(any(URI.class), eq(HttpMethod.GET), eq("/v1/accounting/invoices")))
                .thenReturn((exchange, request) -> {
                    captured.set(request);
                    return Mono.just(ok);
                });
        DiscoveredOperation operation = new DiscoveredOperation(
                "accounting_listinvoices",
                "List invoices",
                "GET",
                "/v1/accounting/invoices",
                "http://api-gateway:8080",
                null);
        OpenApiOperationExecutor executor =
                new OpenApiOperationExecutor(proxyFactory, operation, new ObjectMapper(), TIMEOUT, "Bearer test-token");

        executor.execute(
                ToolExecutionRequest.builder()
                        .name("accounting_listinvoices")
                        .arguments("{}")
                        .build(),
                null);

        assertThat(captured.get()).isNotNull();
        Object headers = captured.get().arguments().get("headers");
        assertThat(headers).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> headerMap = (Map<String, Object>) headers;
        assertThat(headerMap).containsEntry("Authorization", "Bearer test-token");
    }

    @Test
    @DisplayName("execute returns a controlled error (no proxy call) when service_id is missing")
    void execute_missingServiceId_returnsControlledError() {
        OperationProxyFactory proxyFactory = mock(OperationProxyFactory.class);
        DiscoveredOperation operation = new DiscoveredOperation(
                "accounting_listinvoices", "List invoices", "GET", "/v1/accounting/invoices", null, null);
        OpenApiOperationExecutor executor =
                new OpenApiOperationExecutor(proxyFactory, operation, new ObjectMapper(), TIMEOUT, null);

        String result = executor.execute(
                ToolExecutionRequest.builder()
                        .name("accounting_listinvoices")
                        .arguments("{}")
                        .build(),
                null);

        assertThat(result).startsWith("Error:");
        verifyNoInteractions(proxyFactory);
    }
}
