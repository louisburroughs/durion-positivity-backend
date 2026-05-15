package com.positivity.mcp.internal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.McpServerProperties;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class OpenApiToolMapperTest {

    private static final URI GATEWAY_URI = URI.create("http://gateway.test");

    @Test
    @DisplayName("toAggregateToolSpecifications derives domain from first non-version path segment and names tool {domain}_{operationId}")
    void toAggregateToolSpecifications_generatesDomainPrefixedToolName_forVersionedPath() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any())).thenReturn((ex, req) -> Mono.empty());

        OpenApiToolMapper mapper = new OpenApiToolMapper(propertiesWithExclusions(List.of()), mockFactory);
        OpenAPI openApi = openApiWith(Map.of("/v1/accounting/invoices", getItem("listInvoices", "List invoices")));

        List<McpServerFeatures.AsyncToolSpecification> specs =
                mapper.toAggregateToolSpecifications(GATEWAY_URI, openApi);

        assertThat(specs).hasSize(1);
        assertThat(specs.getFirst().tool().name()).isEqualTo("accounting_listinvoices");
    }

    @Test
    @DisplayName("toAggregateToolSpecifications uses first non-version segment when path starts with domain prefix before version")
    void toAggregateToolSpecifications_usesFirstSegment_whenDomainPrecedesVersion() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any())).thenReturn((ex, req) -> Mono.empty());

        OpenApiToolMapper mapper = new OpenApiToolMapper(propertiesWithExclusions(List.of()), mockFactory);
        OpenAPI openApi =
                openApiWith(Map.of("/catalog/v1/catalog/products/{productId}", getItem("getProduct", "Get product")));

        List<McpServerFeatures.AsyncToolSpecification> specs =
                mapper.toAggregateToolSpecifications(GATEWAY_URI, openApi);

        assertThat(specs).hasSize(1);
        assertThat(specs.getFirst().tool().name()).isEqualTo("catalog_getproduct");
    }

    @Test
    @DisplayName("toAggregateToolSpecifications excludes paths containing configured excluded-path-fragments")
    void toAggregateToolSpecifications_excludesPaths_whenFragmentsConfigured() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any())).thenReturn((ex, req) -> Mono.empty());

        McpServerProperties props = propertiesWithExclusions(List.of("/admin/", "/actuator/", "/internal/"));
        OpenApiToolMapper mapper = new OpenApiToolMapper(props, mockFactory);
        OpenAPI openApi = openApiWith(Map.of(
                "/v1/accounting/invoices", getItem("listInvoices", "List invoices"),
                "/v1/admin/users", getItem("listUsers", "List users"),
                "/actuator/health", getItem("health", "Health check"),
                "/v1/internal/config", getItem("getConfig", "Get config")));

        List<McpServerFeatures.AsyncToolSpecification> specs =
                mapper.toAggregateToolSpecifications(GATEWAY_URI, openApi);

        assertThat(specs).hasSize(1);
        assertThat(specs.getFirst().tool().name()).isEqualTo("accounting_listinvoices");
    }

    @Test
    @DisplayName("toAggregateToolSpecifications returns empty list when OpenAPI has no paths")
    void toAggregateToolSpecifications_returnsEmpty_whenNoPaths() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        OpenApiToolMapper mapper = new OpenApiToolMapper(propertiesWithExclusions(List.of()), mockFactory);
        OpenAPI openApi = new OpenAPI();

        List<McpServerFeatures.AsyncToolSpecification> specs =
                mapper.toAggregateToolSpecifications(GATEWAY_URI, openApi);

        assertThat(specs).isEmpty();
    }

    @Test
    @DisplayName("toAggregateToolSpecifications excludes paths not matching configured included-path-prefixes allowlist")
    void toAggregateToolSpecifications_excludesPaths_whenNotInAllowlist() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any())).thenReturn((ex, req) -> Mono.empty());

        McpServerProperties props = propertiesWithPrefixesAndExclusions(List.of("/v1/accounting/"), List.of());
        OpenApiToolMapper mapper = new OpenApiToolMapper(props, mockFactory);
        OpenAPI openApi = openApiWith(Map.of(
                "/v1/accounting/invoices", getItem("listInvoices", "List invoices"),
                "/v1/orders/items", getItem("listItems", "List items")));

        List<McpServerFeatures.AsyncToolSpecification> specs =
                mapper.toAggregateToolSpecifications(GATEWAY_URI, openApi);

        assertThat(specs).hasSize(1);
        assertThat(specs.getFirst().tool().name()).isEqualTo("accounting_listinvoices");
    }

    // --- helpers ---

    private static McpServerProperties propertiesWithExclusions(List<String> excludedFragments) {
        return new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                List.of(),
                List.of(),
                null,
                excludedFragments);
    }

    private static McpServerProperties propertiesWithPrefixesAndExclusions(
            List<String> includedPrefixes, List<String> excludedFragments) {
        return new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                List.of(),
                includedPrefixes,
                null,
                excludedFragments);
    }

    private static OpenAPI openApiWith(Map<String, PathItem> pathItems) {
        Paths paths = new Paths();
        pathItems.forEach(paths::addPathItem);
        OpenAPI openApi = new OpenAPI();
        openApi.setPaths(paths);
        return openApi;
    }

    private static PathItem getItem(String operationId, String summary) {
        Operation op = new Operation();
        op.setOperationId(operationId);
        op.setSummary(summary);
        PathItem item = new PathItem();
        item.setGet(op);
        return item;
    }
}
