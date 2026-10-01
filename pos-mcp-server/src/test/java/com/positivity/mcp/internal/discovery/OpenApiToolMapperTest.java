package com.positivity.mcp.internal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
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
    @DisplayName(
            "toAggregateToolSpecifications derives domain from first non-version path segment and names tool {domain}_{operationId}")
    void toAggregateToolSpecifications_generatesDomainPrefixedToolName_forVersionedPath() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any(), anyBoolean())).thenReturn((ex, req) -> Mono.empty());

        OpenApiToolMapper mapper = new OpenApiToolMapper(propertiesWithExclusions(List.of()), mockFactory);
        OpenAPI openApi = openApiWith(Map.of("/v1/accounting/invoices", getItem("listInvoices", "List invoices")));

        List<McpServerFeatures.AsyncToolSpecification> specs =
                mapper.toAggregateToolSpecifications(GATEWAY_URI, openApi);

        assertThat(specs).hasSize(1);
        assertThat(specs.getFirst().tool().name()).isEqualTo("accounting_listinvoices");
    }

    @Test
    @DisplayName("aggregate tools carry MCP annotations derived from the HTTP method")
    void toAggregateToolSpecifications_setsAnnotationsFromMethod() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any(), anyBoolean())).thenReturn((ex, req) -> Mono.empty());
        OpenApiToolMapper mapper = new OpenApiToolMapper(propertiesWithExclusions(List.of()), mockFactory);

        Operation post = new Operation();
        post.setOperationId("createInvoice");
        post.setSummary("Create invoice");
        PathItem postItem = new PathItem();
        postItem.setPost(post);
        OpenAPI openApi = openApiWith(Map.of(
                "/v1/accounting/invoices",
                getItem("listInvoices", "List invoices"),
                "/v1/accounting/invoices/new",
                postItem));

        List<McpServerFeatures.AsyncToolSpecification> specs =
                mapper.toAggregateToolSpecifications(GATEWAY_URI, openApi);

        McpSchema.Tool getTool = specs.stream()
                .map(McpServerFeatures.AsyncToolSpecification::tool)
                .filter(t -> t.name().equals("accounting_listinvoices"))
                .findFirst()
                .orElseThrow();
        assertThat(getTool.annotations().readOnlyHint()).isTrue();
        assertThat(getTool.annotations().idempotentHint()).isTrue();
        assertThat(getTool.annotations().openWorldHint()).isTrue();

        McpSchema.Tool postTool = specs.stream()
                .map(McpServerFeatures.AsyncToolSpecification::tool)
                .filter(t -> t.name().equals("accounting_createinvoice"))
                .findFirst()
                .orElseThrow();
        assertThat(postTool.annotations().readOnlyHint()).isFalse();
        assertThat(postTool.annotations().destructiveHint()).isFalse();
        assertThat(postTool.annotations().idempotentHint()).isFalse();
        assertThat(postTool.annotations().openWorldHint()).isTrue();
    }

    @Test
    @DisplayName(
            "toAggregateToolSpecifications uses first non-version segment when path starts with domain prefix before version")
    void toAggregateToolSpecifications_usesFirstSegment_whenDomainPrecedesVersion() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any(), anyBoolean())).thenReturn((ex, req) -> Mono.empty());

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
        when(mockFactory.handlerForBaseUri(any(), any(), any(), anyBoolean())).thenReturn((ex, req) -> Mono.empty());

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
    @DisplayName(
            "toAggregateToolSpecifications excludes paths not matching configured included-path-prefixes allowlist")
    void toAggregateToolSpecifications_excludesPaths_whenNotInAllowlist() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any(), anyBoolean())).thenReturn((ex, req) -> Mono.empty());

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

    @Test
    @DisplayName("toDiscoveredOperations surfaces method/path/serviceId execution coordinates (Gate 3 G3.1)")
    void toDiscoveredOperations_surfacesExecutionCoordinates() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        OpenApiToolMapper mapper = new OpenApiToolMapper(propertiesWithExclusions(List.of()), mockFactory);
        OpenAPI openApi = openApiWith(Map.of("/v1/accounting/invoices", getItem("listInvoices", "List invoices")));

        List<DiscoveredOperation> ops = mapper.toDiscoveredOperations("pos-api-gateway", openApi);

        assertThat(ops).hasSize(1);
        DiscoveredOperation op = ops.getFirst();
        assertThat(op.name()).isEqualTo("accounting_listinvoices");
        assertThat(op.description()).isEqualTo("List invoices");
        assertThat(op.httpMethod()).isEqualTo("GET");
        assertThat(op.httpPath()).isEqualTo("/v1/accounting/invoices");
        assertThat(op.serviceId()).isEqualTo("pos-api-gateway");
        assertThat(op.inputSchema()).isNull();
        assertThat(op.isExecutable()).isTrue();
    }

    @Test
    @DisplayName("toDiscoveredOperations reads the x-required-permissions extension (#781)")
    void toDiscoveredOperations_readsRequiredPermissions() {
        OpenApiToolMapper mapper =
                new OpenApiToolMapper(propertiesWithExclusions(List.of()), mock(OperationProxyFactory.class));
        Operation op = new Operation();
        op.setOperationId("listInvoices");
        op.setSummary("List invoices");
        op.addExtension("x-required-permissions", List.of("accounting:invoice:view", "AUTHENTICATED"));
        PathItem item = new PathItem();
        item.setGet(op);
        OpenAPI openApi = openApiWith(Map.of("/v1/accounting/invoices", item));

        List<DiscoveredOperation> ops = mapper.toDiscoveredOperations("pos-api-gateway", openApi);

        assertThat(ops).hasSize(1);
        assertThat(ops.getFirst().requiredPermissions()).containsExactly("accounting:invoice:view", "AUTHENTICATED");
    }

    @Test
    @DisplayName("toDiscoveredOperations is fail-closed: no x-required-permissions => empty (#781)")
    void toDiscoveredOperations_failClosedWhenNoRequiredPermissions() {
        OpenApiToolMapper mapper =
                new OpenApiToolMapper(propertiesWithExclusions(List.of()), mock(OperationProxyFactory.class));
        OpenAPI openApi = openApiWith(Map.of("/v1/accounting/invoices", getItem("listInvoices", "List invoices")));

        List<DiscoveredOperation> ops = mapper.toDiscoveredOperations("pos-api-gateway", openApi);

        assertThat(ops.getFirst().requiredPermissions()).isEmpty();
    }

    @Test
    @DisplayName("toDiscoveredOperations applies the same exclusion filtering as spec generation")
    void toDiscoveredOperations_excludesConfiguredFragments() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        McpServerProperties props = propertiesWithExclusions(List.of("/admin/"));
        OpenApiToolMapper mapper = new OpenApiToolMapper(props, mockFactory);
        OpenAPI openApi = openApiWith(Map.of(
                "/v1/accounting/invoices", getItem("listInvoices", "List invoices"),
                "/v1/admin/settings", getItem("getSettings", "Get settings")));

        List<DiscoveredOperation> ops = mapper.toDiscoveredOperations("pos-api-gateway", openApi);

        assertThat(ops).extracting(DiscoveredOperation::name).containsExactly("accounting_listinvoices");
    }

    // ---- #2370: audit / platform-event writes are never agent tools -----------------------------

    private static final List<String> WRITE_EXCLUSIONS = List.of(
            "^/security-service/v1/audit/", "^/event-receiver/v1/events(/|$)", "^/event-receiver/v1/eventTypes(/|$)");

    /** The routing-prefixed paths discovery sees: audit + platform-event surfaces and business look-alikes. */
    private static OpenAPI auditAndEventPaths() {
        PathItem auditEvents = new PathItem();
        auditEvents.setGet(operation("searchAuditEvents"));
        auditEvents.setPost(operation("createAuditEvent"));
        PathItem auditEventsWildcard = new PathItem();
        auditEventsWildcard.setPut(operation("rejectAuditEventUpdate"));
        auditEventsWildcard.setDelete(operation("rejectAuditEventDelete"));
        PathItem events = new PathItem();
        events.setGet(operation("queryEventsByEntity"));
        events.setPost(operation("receiveEvent"));
        PathItem eventTypeById = new PathItem();
        eventTypeById.setGet(operation("getEventTypeById"));
        eventTypeById.setPut(operation("updateEventType"));
        eventTypeById.setDelete(operation("deleteEventType"));
        // Business paths that merely contain "audit" / "events": a bare fragment would drop these.
        PathItem accountingAudit = new PathItem();
        accountingAudit.setPost(operation("recordRefundAudit"));
        PathItem accountingEventRetry = new PathItem();
        accountingEventRetry.setPost(operation("retryAccountingEvent"));
        return openApiWith(Map.of(
                "/security-service/v1/audit/events", auditEvents,
                "/security-service/v1/audit/events/**", auditEventsWildcard,
                "/event-receiver/v1/events", events,
                "/event-receiver/v1/eventTypes/{id}", eventTypeById,
                "/accounting/v1/accounting/audit/refund", accountingAudit,
                "/accounting/v1/accounting/events/{eventId}/retry", accountingEventRetry));
    }

    @Test
    @DisplayName("#2370: toDiscoveredOperations drops non-GET operations on excluded-write paths, keeps GET and "
            + "business look-alikes")
    void toDiscoveredOperations_dropsWritesOnExcludedWritePaths_keepsReadsAndBusinessPaths() {
        OpenApiToolMapper mapper = new OpenApiToolMapper(
                propertiesWithWriteExclusions(WRITE_EXCLUSIONS), mock(OperationProxyFactory.class));

        List<DiscoveredOperation> ops = mapper.toDiscoveredOperations("pos-api-gateway", auditAndEventPaths());

        assertThat(ops)
                .extracting(DiscoveredOperation::name)
                .containsExactlyInAnyOrder(
                        "security-service_searchauditevents",
                        "event-receiver_queryeventsbyentity",
                        "event-receiver_geteventtypebyid",
                        "accounting_recordrefundaudit",
                        "accounting_retryaccountingevent");
        assertThat(ops)
                .filteredOn(op ->
                        op.name().startsWith("security-service_") || op.name().startsWith("event-receiver_"))
                .extracting(DiscoveredOperation::httpMethod)
                .containsOnly("GET");
    }

    @Test
    @DisplayName("#2370: toAggregateToolSpecifications applies the same write exclusion to the MCP tool specs")
    void toAggregateToolSpecifications_dropsWritesOnExcludedWritePaths() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any(), anyBoolean())).thenReturn((ex, req) -> Mono.empty());
        OpenApiToolMapper mapper = new OpenApiToolMapper(propertiesWithWriteExclusions(WRITE_EXCLUSIONS), mockFactory);

        List<McpServerFeatures.AsyncToolSpecification> specs =
                mapper.toAggregateToolSpecifications(GATEWAY_URI, auditAndEventPaths());

        assertThat(specs)
                .extracting(spec -> spec.tool().name())
                .containsExactlyInAnyOrder(
                        "security-service_searchauditevents",
                        "event-receiver_queryeventsbyentity",
                        "event-receiver_geteventtypebyid",
                        "accounting_recordrefundaudit",
                        "accounting_retryaccountingevent");
    }

    @Test
    @DisplayName("#2370: excludedWriteDomains names the domains whose operations the write exclusion dropped")
    void excludedWriteDomains_namesDomainsOfDroppedOperations() {
        OpenApiToolMapper mapper = new OpenApiToolMapper(
                propertiesWithWriteExclusions(WRITE_EXCLUSIONS), mock(OperationProxyFactory.class));

        assertThat(mapper.excludedWriteDomains(auditAndEventPaths()))
                .containsExactlyInAnyOrder("security-service", "event-receiver");
        assertThat(new OpenApiToolMapper(propertiesWithExclusions(List.of()), mock(OperationProxyFactory.class))
                        .excludedWriteDomains(auditAndEventPaths()))
                .isEmpty();
    }

    @Test
    @DisplayName("#2370: with no excluded-write patterns configured every write stays discoverable")
    void toDiscoveredOperations_keepsWrites_whenNoWriteExclusionConfigured() {
        OpenApiToolMapper mapper =
                new OpenApiToolMapper(propertiesWithExclusions(List.of()), mock(OperationProxyFactory.class));

        List<DiscoveredOperation> ops = mapper.toDiscoveredOperations("pos-api-gateway", auditAndEventPaths());

        assertThat(ops).hasSize(11);
        assertThat(ops).extracting(DiscoveredOperation::name).contains("security-service_createauditevent");
    }

    private static Operation operation(String operationId) {
        Operation operation = new Operation();
        operation.setOperationId(operationId);
        operation.setSummary(operationId);
        return operation;
    }

    private static McpServerProperties propertiesWithWriteExclusions(List<String> excludedWritePathPatterns) {
        return new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                List.of(),
                List.of(),
                null,
                List.of(),
                excludedWritePathPatterns,
                Map.of());
    }

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
                excludedFragments,
                List.of(),
                Map.of());
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
                excludedFragments,
                List.of(),
                Map.of());
    }

    private static OpenAPI openApiWith(Map<String, PathItem> pathItems) {
        Paths paths = new Paths();
        pathItems.forEach(paths::addPathItem);
        OpenAPI openApi = new OpenAPI();
        openApi.setPaths(paths);
        return openApi;
    }

    @Test
    @DisplayName("toDiscoveredOperations persists query parameters as input_schema JSON")
    void toDiscoveredOperations_persistsQueryParamsAsInputSchema() {
        OpenApiToolMapper mapper =
                new OpenApiToolMapper(propertiesWithExclusions(List.of()), mock(OperationProxyFactory.class));

        Operation op = new Operation();
        op.setOperationId("searchOrders");
        var status = new io.swagger.v3.oas.models.parameters.QueryParameter();
        status.setName("status");
        status.setRequired(true);
        status.setSchema(new io.swagger.v3.oas.models.media.StringSchema());
        op.setParameters(List.of(status));
        PathItem item = new PathItem();
        item.setGet(op);
        OpenAPI openApi = openApiWith(Map.of("/v1/order/orders", item));

        List<DiscoveredOperation> ops = mapper.toDiscoveredOperations("http://api-gateway:8080", openApi);

        assertThat(ops).hasSize(1);
        assertThat(ops.getFirst().inputSchema())
                .contains("\"name\":\"status\"")
                .contains("\"type\":\"string\"")
                .contains("\"required\":true");
    }

    @Test
    @DisplayName("aggregate object (2xx $ref) response yields a permissive, self-contained outputSchema")
    void toAggregateToolSpecifications_emitsOutputSchema_forObjectResponse() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any(), anyBoolean())).thenReturn((ex, req) -> Mono.empty());
        OpenApiToolMapper mapper = new OpenApiToolMapper(propertiesWithExclusions(List.of()), mockFactory);

        Operation op = new Operation();
        op.setOperationId("getInvoice");
        op.setSummary("Get invoice");
        op.setResponses(jsonResponse("200", refSchema("InvoiceResponse")));

        var component = new io.swagger.v3.oas.models.media.ObjectSchema();
        component.setDescription("An invoice.");
        var amount = new io.swagger.v3.oas.models.media.NumberSchema();
        amount.setDescription("Total amount due.");
        component.addProperty("amount", amount);
        component.addProperty("status", new io.swagger.v3.oas.models.media.StringSchema());

        PathItem item = new PathItem();
        item.setGet(op);
        OpenAPI openApi = openApiWith(Map.of("/v1/accounting/invoices/{id}", item));
        openApi.setComponents(new io.swagger.v3.oas.models.Components().addSchemas("InvoiceResponse", component));

        List<McpServerFeatures.AsyncToolSpecification> specs =
                mapper.toAggregateToolSpecifications(GATEWAY_URI, openApi);

        Map<String, Object> outputSchema = specs.getFirst().tool().outputSchema();
        assertThat(outputSchema).isNotNull();
        assertThat(outputSchema).containsEntry("type", "object");
        assertThat(outputSchema).containsEntry("additionalProperties", Boolean.TRUE);
        assertThat(outputSchema).containsEntry("title", "InvoiceResponse");
        assertThat(outputSchema).containsEntry("description", "An invoice.");
        assertThat(outputSchema).doesNotContainKey("required");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) outputSchema.get("properties");
        assertThat(properties).containsOnlyKeys("amount", "status");
        @SuppressWarnings("unchecked")
        Map<String, Object> amountProp = (Map<String, Object>) properties.get("amount");
        // Descriptions only — never a type or $ref, so any JSON object validates against the schema.
        assertThat(amountProp).containsEntry("description", "Total amount due.");
        assertThat(amountProp).doesNotContainKey("type");
        assertThat(specs.getFirst().tool().outputSchema()).doesNotContainKey("$ref");
    }

    @Test
    @DisplayName("array-typed responses carry no outputSchema (structured output requires an object)")
    void toAggregateToolSpecifications_noOutputSchema_forArrayResponse() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any(), anyBoolean())).thenReturn((ex, req) -> Mono.empty());
        OpenApiToolMapper mapper = new OpenApiToolMapper(propertiesWithExclusions(List.of()), mockFactory);

        Operation op = new Operation();
        op.setOperationId("listInvoices");
        op.setSummary("List invoices");
        op.setResponses(jsonResponse("200", new io.swagger.v3.oas.models.media.ArraySchema()));
        PathItem item = new PathItem();
        item.setGet(op);
        OpenAPI openApi = openApiWith(Map.of("/v1/accounting/invoices", item));

        List<McpServerFeatures.AsyncToolSpecification> specs =
                mapper.toAggregateToolSpecifications(GATEWAY_URI, openApi);

        assertThat(specs.getFirst().tool().outputSchema()).isNull();
    }

    @Test
    @DisplayName("operations with no declared response body carry no outputSchema")
    void toAggregateToolSpecifications_noOutputSchema_whenNoResponse() {
        OperationProxyFactory mockFactory = mock(OperationProxyFactory.class);
        when(mockFactory.handlerForBaseUri(any(), any(), any(), anyBoolean())).thenReturn((ex, req) -> Mono.empty());
        OpenApiToolMapper mapper = new OpenApiToolMapper(propertiesWithExclusions(List.of()), mockFactory);
        OpenAPI openApi = openApiWith(Map.of("/v1/accounting/invoices", getItem("listInvoices", "List invoices")));

        List<McpServerFeatures.AsyncToolSpecification> specs =
                mapper.toAggregateToolSpecifications(GATEWAY_URI, openApi);

        assertThat(specs.getFirst().tool().outputSchema()).isNull();
    }

    private static io.swagger.v3.oas.models.responses.ApiResponses jsonResponse(String code, Schema<?> schema) {
        var media = new io.swagger.v3.oas.models.media.MediaType().schema(schema);
        var content = new io.swagger.v3.oas.models.media.Content().addMediaType("application/json", media);
        var response = new io.swagger.v3.oas.models.responses.ApiResponse()
                .description("ok")
                .content(content);
        return new io.swagger.v3.oas.models.responses.ApiResponses().addApiResponse(code, response);
    }

    private static Schema<?> refSchema(String componentName) {
        var schema = new io.swagger.v3.oas.models.media.Schema<>();
        schema.set$ref("#/components/schemas/" + componentName);
        return schema;
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
