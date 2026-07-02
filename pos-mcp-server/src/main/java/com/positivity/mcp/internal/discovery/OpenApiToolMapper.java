package com.positivity.mcp.internal.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.shared.id.UUIDv7Generator;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OpenApiToolMapper {

    private static final String OBJECT = "object";
    private static final String DESCRIPTION = "description";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUERY_IN = "query";
    private final McpServerProperties properties;
    private final OperationProxyFactory proxyFactory;

    public OpenApiToolMapper(@NonNull McpServerProperties properties, @NonNull OperationProxyFactory proxyFactory) {
        this.properties = properties;
        this.proxyFactory = proxyFactory;
    }

    @NonNull
    public List<McpServerFeatures.AsyncToolSpecification> toToolSpecifications(
            @NonNull String serviceId, @NonNull URI baseUri, @NonNull OpenAPI openApi) {
        var specs = new ArrayList<McpServerFeatures.AsyncToolSpecification>();
        if (openApi.getPaths() == null) {
            return specs;
        }

        openApi.getPaths().forEach((path, pathItem) -> {
            if (!properties.includesPath(path)) {
                return;
            }
            addOperation(specs, serviceId, baseUri, path, pathItem.getGet(), HttpMethod.GET);
            addOperation(specs, serviceId, baseUri, path, pathItem.getPost(), HttpMethod.POST);
            addOperation(specs, serviceId, baseUri, path, pathItem.getPut(), HttpMethod.PUT);
            addOperation(specs, serviceId, baseUri, path, pathItem.getDelete(), HttpMethod.DELETE);
            addOperation(specs, serviceId, baseUri, path, pathItem.getPatch(), HttpMethod.PATCH);
        });
        return specs;
    }

    /**
     * Maps aggregate OpenAPI operations to tool specifications using the gateway base URI directly.
     * Tool names are derived as {@code {domain}_{operationId}} where the domain is the first
     * non-version path segment (e.g. {@code /v1/accounting/invoices} → {@code accounting}).
     * Paths matching any configured {@code excludedPathFragments} are skipped.
     */
    @NonNull
    public List<McpServerFeatures.AsyncToolSpecification> toAggregateToolSpecifications(
            @NonNull URI gatewayBaseUri, @NonNull OpenAPI openApi) {
        var specs = new ArrayList<McpServerFeatures.AsyncToolSpecification>();
        if (openApi.getPaths() == null) {
            return specs;
        }
        openApi.getPaths().forEach((path, pathItem) -> {
            if (!properties.includesPath(path) || properties.excludesPath(path)) {
                return;
            }
            addAggregateOperation(specs, gatewayBaseUri, path, pathItem.getGet(), HttpMethod.GET);
            addAggregateOperation(specs, gatewayBaseUri, path, pathItem.getPost(), HttpMethod.POST);
            addAggregateOperation(specs, gatewayBaseUri, path, pathItem.getPut(), HttpMethod.PUT);
            addAggregateOperation(specs, gatewayBaseUri, path, pathItem.getDelete(), HttpMethod.DELETE);
            addAggregateOperation(specs, gatewayBaseUri, path, pathItem.getPatch(), HttpMethod.PATCH);
        });
        return specs;
    }

    /**
     * Gate 3 (G3.1): surfaces the execution coordinates of each allow-listed aggregate operation as
     * {@link DiscoveredOperation}s, so they can be persisted as {@code mcp_tool} rows
     * ({@code source='openapi'}). Same allow/deny filtering as {@link #toAggregateToolSpecifications}.
     *
     * <p>{@code serviceId} is supplied by the caller (the gateway service id) because aggregate-first
     * discovery routes every operation through the gateway. {@code inputSchema} is left null here and
     * populated by the persistence step. This method builds no proxy handlers — it is pure metadata.
     */
    @NonNull
    public List<DiscoveredOperation> toDiscoveredOperations(@NonNull String serviceId, @NonNull OpenAPI openApi) {
        var operations = new ArrayList<DiscoveredOperation>();
        if (openApi.getPaths() == null) {
            return operations;
        }
        openApi.getPaths().forEach((path, pathItem) -> {
            if (!properties.includesPath(path) || properties.excludesPath(path)) {
                return;
            }
            addDiscoveredOperation(operations, serviceId, path, pathItem.getGet(), HttpMethod.GET);
            addDiscoveredOperation(operations, serviceId, path, pathItem.getPost(), HttpMethod.POST);
            addDiscoveredOperation(operations, serviceId, path, pathItem.getPut(), HttpMethod.PUT);
            addDiscoveredOperation(operations, serviceId, path, pathItem.getDelete(), HttpMethod.DELETE);
            addDiscoveredOperation(operations, serviceId, path, pathItem.getPatch(), HttpMethod.PATCH);
        });
        return operations;
    }

    private void addDiscoveredOperation(
            @NonNull List<DiscoveredOperation> operations,
            @NonNull String serviceId,
            @NonNull String path,
            Operation operation,
            @NonNull HttpMethod method) {
        if (operation == null) {
            return;
        }
        String domain = extractDomain(path);
        String operationId = buildOperationId(operation);
        String toolName = sanitizeName(domain + "_" + operationId);
        String title = Optional.ofNullable(operation.getSummary()).orElse(operationId);
        String description = Optional.ofNullable(operation.getDescription()).orElse(title);
        operations.add(new DiscoveredOperation(
                toolName, description, method.name(), path, serviceId, buildQueryParamSchemaJson(operation)));
    }

    /**
     * Compact JSON of the operation's query parameters ({@code {"query":[{"name","type","required"}]}}),
     * persisted as {@code input_schema} so {@link com.positivity.mcp.internal.service.OpenApiToolProvider}
     * can type them for the model. Returns null when the operation has no query parameters.
     */
    private @Nullable String buildQueryParamSchemaJson(@NonNull Operation operation) {
        if (operation.getParameters() == null) {
            return null;
        }
        List<Map<String, Object>> query = new ArrayList<>();
        for (Parameter parameter : operation.getParameters()) {
            if (parameter == null || !QUERY_IN.equalsIgnoreCase(parameter.getIn()) || parameter.getName() == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", parameter.getName());
            entry.put(
                    "type",
                    parameter.getSchema() != null && parameter.getSchema().getType() != null
                            ? parameter.getSchema().getType()
                            : "string");
            entry.put("required", Boolean.TRUE.equals(parameter.getRequired()));
            query.add(entry);
        }
        if (query.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(Map.of(QUERY_IN, query));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void addAggregateOperation(
            @NonNull List<McpServerFeatures.AsyncToolSpecification> specs,
            @NonNull URI gatewayBaseUri,
            @NonNull String path,
            Operation operation,
            @NonNull HttpMethod method) {
        if (operation == null) {
            return;
        }
        String domain = extractDomain(path);
        String operationId = buildOperationId(operation);
        String toolName = sanitizeName(domain + "_" + operationId);
        String title = Optional.ofNullable(operation.getSummary()).orElse(operationId);
        String description = Optional.ofNullable(operation.getDescription()).orElse(title);

        var inputSchema = buildInputSchema(method, path, operation);
        var tool = McpSchema.Tool.builder()
                .name(toolName)
                .title(title)
                .description(description)
                .inputSchema(inputSchema)
                .build();

        var handler = proxyFactory.handlerForBaseUri(gatewayBaseUri, method, path);
        specs.add(McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build());
    }

    public static String extractDomain(@NonNull String path) {
        for (String segment : path.split("/")) {
            if (segment.isBlank() || segment.matches("v\\d+")) {
                continue;
            }
            return segment;
        }
        return "unknown";
    }

    private void addOperation(
            @NonNull List<McpServerFeatures.AsyncToolSpecification> specs,
            @NonNull String serviceId,
            @NonNull URI baseUri,
            @NonNull String path,
            Operation operation,
            @NonNull HttpMethod method) {
        if (operation == null) {
            return;
        }

        String operationId = buildOperationId(operation);
        String toolName = sanitizeName(serviceId + "_" + operationId);
        String title = Optional.ofNullable(operation.getSummary()).orElse(operationId);
        String description = Optional.ofNullable(operation.getDescription()).orElse(title);

        var inputSchema = buildInputSchema(method, path, operation);
        var tool = McpSchema.Tool.builder()
                .name(toolName)
                .title(title)
                .description(description)
                .inputSchema(inputSchema)
                .build();

        var handler = proxyFactory.handler(serviceId, method, path);
        specs.add(McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build());
    }

    private McpSchema.JsonSchema buildInputSchema(
            @NonNull HttpMethod method, @NonNull String path, @NonNull Operation operation) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "httpMethod",
                Map.of(
                        "type",
                        "string",
                        "const",
                        method.name(),
                        DESCRIPTION,
                        "HTTP method for the underlying API call"));
        properties.put("path", Map.of("type", "string", "const", path, DESCRIPTION, "Path template for the API call"));
        properties.put("pathParams", Map.of("type", OBJECT, DESCRIPTION, "Path parameters keyed by template name"));
        properties.put("queryParams", Map.of("type", OBJECT, DESCRIPTION, "Query string parameters"));
        properties.put("headers", Map.of("type", OBJECT, DESCRIPTION, "Additional HTTP headers to include"));
        if (operation.getRequestBody() != null) {
            properties.put(
                    "body", Map.of("type", OBJECT, DESCRIPTION, "Request body payload matching the operation schema"));
        }

        return new McpSchema.JsonSchema(
                OBJECT, properties, List.of("httpMethod", "path"), Boolean.TRUE, Map.of(), Map.of());
    }

    private String sanitizeName(@NonNull String raw) {
        String sanitized = raw.toLowerCase(Locale.US).replaceAll("[^a-z0-9_\\-]", "_");
        if (!StringUtils.hasText(sanitized)) {
            return "tool_" + UUIDv7Generator.generate().toString().replace("-", "");
        }
        return sanitized;
    }

    private String buildOperationId(@NonNull Operation operation) {
        if (StringUtils.hasText(operation.getOperationId())) {
            return operation.getOperationId();
        }
        if (StringUtils.hasText(operation.getSummary())) {
            return operation.getSummary().replace(' ', '_');
        }
        return "op_" + UUIDv7Generator.generate();
    }
}
