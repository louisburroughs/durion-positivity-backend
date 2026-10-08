package com.positivity.mcp.internal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * ADR-0072 Decision 4 / CHK-010 (Security ruling on #2621, 2026-10-08): an operation that returns a RESTRICTED
 * value is never an agent tool. Either marker excludes it, for every HTTP method, before any include rule, and with
 * no configuration at all: an {@code x-required-permissions} entry whose action is {@code reveal}, or a path ending
 * in {@code /reveal}. The properties below carry NO write exclusion, so a rule moved into configuration fails here.
 */
@DisplayName("Reveal operations are never discovered as tools (ADR-0072 Decision 4, CHK-010)")
class OpenApiToolMapperRevealExclusionTest {

    private static final URI GATEWAY_URI = URI.create("http://gateway.test");

    /** No exclusions of any kind; an include rule that matches every synthetic path below. */
    private static McpServerProperties includeEverything() {
        return new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                List.of(),
                List.of("/supplier/", "/people/", "/vehicle-fitment/", "/v1/"),
                null,
                List.of(),
                List.of(),
                Map.of());
    }

    private static OpenApiToolMapper mapper() {
        OperationProxyFactory factory = mock(OperationProxyFactory.class);
        when(factory.handlerForBaseUri(any(), any(), any(), anyBoolean()))
                .thenReturn((exchange, request) -> Mono.empty());
        when(factory.handler(any(String.class), any(), any(), anyBoolean()))
                .thenReturn((exchange, request) -> Mono.empty());
        return new OpenApiToolMapper(includeEverything(), factory);
    }

    private static Operation operation(String operationId, String... permissions) {
        Operation operation = new Operation().operationId(operationId).summary(operationId);
        operation.addExtension("x-required-permissions", List.of(permissions));
        return operation;
    }

    /** Each case on each method, plus an ordinary control operation that must survive. */
    private static OpenAPI spec() {
        Map<String, PathItem> items = new LinkedHashMap<>();
        // Only the permission marker, on a path that does not end in /reveal, on a GET.
        items.put(
                "/people/v1/people/employees/{id}/pii",
                new PathItem().get(operation("getEmployeePii", "people:employee_pii:reveal")));
        // Only the path marker, with an ordinary permission, on a POST and a GET.
        items.put(
                "/supplier/v1/supplier/vendors/{id}/notes/{noteId}/reveal",
                new PathItem()
                        .post(operation("revealNote", "supplier:vendor:read"))
                        .get(operation("peekNote", "supplier:vendor:read")));
        // Only the permission marker, with a hyphenated and camelCase domain/resource, on a non-/reveal path.
        items.put(
                "/vehicle-fitment/v1/vehicle-fitment/owners/{id}",
                new PathItem().get(operation("getOwnerVin", "vehicle-fitment:ownerVin:reveal")));
        // Both markers.
        items.put(
                "/supplier/v1/supplier/vendors/{vendorId}/tax-registrations/{registrationId}/reveal",
                new PathItem().post(operation("revealSupplierVendorTaxRegistration", "supplier:vendor_tax_id:reveal")));
        // Control: neither marker.
        items.put(
                "/supplier/v1/supplier/vendors",
                new PathItem().get(operation("listSupplierVendors", "supplier:vendor:read")));
        Paths paths = new Paths();
        items.forEach(paths::addPathItem);
        return new OpenAPI().paths(paths);
    }

    @Test
    @DisplayName("aggregate discovery: only-permission, only-path and both-marker operations are excluded on every"
            + " method, though an include rule matches them; the control survives")
    void discoveredOperationsExcludeEitherMarker() {
        List<DiscoveredOperation> discovered = mapper().toDiscoveredOperations("pos-api-gateway", spec());

        assertThat(discovered)
                .extracting(DiscoveredOperation::httpPath)
                .containsExactly("/supplier/v1/supplier/vendors");
    }

    @Test
    @DisplayName("aggregate tool specifications exclude either marker")
    void aggregateToolsExcludeEitherMarker() {
        List<McpServerFeatures.AsyncToolSpecification> tools =
                mapper().toAggregateToolSpecifications(GATEWAY_URI, spec());

        assertThat(tools).extracting(tool -> tool.tool().name()).containsExactly("supplier_listsuppliervendors");
    }

    @Test
    @DisplayName("the per-service Eureka fallback excludes either marker too")
    void perServiceFallbackExcludesEitherMarker() {
        Map<String, PathItem> unprefixed = new LinkedHashMap<>();
        spec().getPaths().forEach((path, item) -> unprefixed.put(path.substring(path.indexOf("/v1/")), item));
        Paths paths = new Paths();
        unprefixed.forEach(paths::addPathItem);

        List<McpServerFeatures.AsyncToolSpecification> tools = mapper().toToolSpecifications(
                        "pos-supplier", URI.create("http://pos-supplier.test"), new OpenAPI().paths(paths));

        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().tool().name())
                .doesNotContain("reveal")
                .doesNotContain("pii")
                .doesNotContain("note");
    }

    @Test
    @DisplayName("a domain whose reveal operation was dropped counts as seen, so a stale tool row is pruned")
    void revealDomainIsSeenForThePrune() {
        assertThat(mapper().excludedWriteDomains(spec())).contains("supplier", "people", "vehicle-fitment");
    }

    @Test
    @DisplayName("each marker on its own is recognised; neither is a false positive")
    void markers() {
        assertThat(OpenApiToolMapper.hasRevealPermission(operation("a", "supplier:vendor_tax_id:reveal")))
                .isTrue();
        assertThat(OpenApiToolMapper.hasRevealPermission(operation("b", "supplier:vendor:read")))
                .isFalse();
        assertThat(OpenApiToolMapper.hasRevealPermission(operation("c", "supplier:reveal:read")))
                .isFalse();
        assertThat(OpenApiToolMapper.hasRevealPermission(operation("d", "vehicle-fitment:ownerVin:reveal")))
                .isTrue();
        assertThat(OpenApiToolMapper.hasRevealPermission(operation("e", "pos2:pii_v2:reveal")))
                .isTrue();
        assertThat(OpenApiToolMapper.hasRevealPermission(operation("f", "supplier:vendor:reveal:extra")))
                .isFalse();
        assertThat(OpenApiToolMapper.hasRevealPermission(new Operation())).isFalse();
        assertThat(OpenApiToolMapper.hasRevealPath("/v1/x/{id}/reveal")).isTrue();
        assertThat(OpenApiToolMapper.hasRevealPath("/v1/x/{id}/tax-id-reveals")).isFalse();
        assertThat(OpenApiToolMapper.hasRevealPath("/v1/x/reveal/{id}")).isFalse();
    }
}
