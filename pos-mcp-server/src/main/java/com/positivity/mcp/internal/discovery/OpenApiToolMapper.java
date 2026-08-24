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
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
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
    private static final String STRING_TYPE = "string";

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
            addOperation(specs, openApi, serviceId, baseUri, path, pathItem.getGet(), HttpMethod.GET);
            addOperation(specs, openApi, serviceId, baseUri, path, pathItem.getPost(), HttpMethod.POST);
            addOperation(specs, openApi, serviceId, baseUri, path, pathItem.getPut(), HttpMethod.PUT);
            addOperation(specs, openApi, serviceId, baseUri, path, pathItem.getDelete(), HttpMethod.DELETE);
            addOperation(specs, openApi, serviceId, baseUri, path, pathItem.getPatch(), HttpMethod.PATCH);
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
            addAggregateOperation(specs, openApi, gatewayBaseUri, path, pathItem.getGet(), HttpMethod.GET);
            addAggregateOperation(specs, openApi, gatewayBaseUri, path, pathItem.getPost(), HttpMethod.POST);
            addAggregateOperation(specs, openApi, gatewayBaseUri, path, pathItem.getPut(), HttpMethod.PUT);
            addAggregateOperation(specs, openApi, gatewayBaseUri, path, pathItem.getDelete(), HttpMethod.DELETE);
            addAggregateOperation(specs, openApi, gatewayBaseUri, path, pathItem.getPatch(), HttpMethod.PATCH);
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
                toolName,
                description,
                method.name(),
                path,
                serviceId,
                buildQueryParamSchemaJson(operation),
                extractRequiredPermissions(operation)));
    }

    /**
     * Reads the {@code x-required-permissions} vendor extension emitted by each service's
     * {@code requiredPermissionsOperationCustomizer} (#781). Fail-closed: when the extension is
     * absent (or not a list), returns an empty list so the discovered op is never selected until a
     * permission is granted — there is <strong>no</strong> {@code AUTHENTICATED} default here.
     */
    private static @NonNull List<String> extractRequiredPermissions(@NonNull Operation operation) {
        Map<String, Object> extensions = operation.getExtensions();
        if (extensions == null) {
            return List.of();
        }
        Object value = extensions.get("x-required-permissions");
        if (!(value instanceof Collection<?> codes)) {
            return List.of();
        }
        return codes.stream()
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .map(String::trim)
                .filter(code -> !code.isBlank())
                .distinct()
                .toList();
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
            if (parameter == null
                    || !QUERY_IN.equalsIgnoreCase(parameter.getIn())
                    || !StringUtils.hasText(parameter.getName())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", parameter.getName());
            entry.put(
                    "type",
                    parameter.getSchema() != null && parameter.getSchema().getType() != null
                            ? parameter.getSchema().getType()
                            : STRING_TYPE);
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
            @NonNull OpenAPI openApi,
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
        var outputSchema = buildOutputSchema(openApi, operation);
        var toolBuilder = McpSchema.Tool.builder()
                .name(toolName)
                .title(title)
                .description(description)
                .inputSchema(inputSchema)
                .annotations(annotationsForMethod(method));
        if (outputSchema != null) {
            toolBuilder.outputSchema(outputSchema);
        }
        var tool = toolBuilder.build();

        var handler = proxyFactory.handlerForBaseUri(gatewayBaseUri, method, path, outputSchema != null);
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

    /**
     * Maps an operation's HTTP method to MCP {@link McpSchema.ToolAnnotations} behavioral hints so
     * clients can reason about tool effects (e.g. gate writes behind confirmation): GET is read-only
     * and idempotent; PUT/DELETE mutate but are idempotent and destructive; POST is additive and
     * non-idempotent; PATCH is destructive and non-idempotent. Every discovered op calls the backend,
     * so openWorldHint is always true. Hints are advisory, not security guarantees — gating is still
     * enforced by permission codes.
     */
    private static McpSchema.ToolAnnotations annotationsForMethod(@NonNull HttpMethod method) {
        boolean readOnly = method == HttpMethod.GET;
        boolean idempotent = method == HttpMethod.GET || method == HttpMethod.PUT || method == HttpMethod.DELETE;
        boolean destructive = method == HttpMethod.PUT || method == HttpMethod.DELETE || method == HttpMethod.PATCH;
        // title left null (the Tool already carries a title); returnDirect left default (null).
        return new McpSchema.ToolAnnotations(null, readOnly, destructive, idempotent, true, null);
    }

    private void addOperation(
            @NonNull List<McpServerFeatures.AsyncToolSpecification> specs,
            @NonNull OpenAPI openApi,
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
        var outputSchema = buildOutputSchema(openApi, operation);
        var toolBuilder = McpSchema.Tool.builder()
                .name(toolName)
                .title(title)
                .description(description)
                .inputSchema(inputSchema)
                .annotations(annotationsForMethod(method));
        if (outputSchema != null) {
            toolBuilder.outputSchema(outputSchema);
        }
        var tool = toolBuilder.build();

        var handler = proxyFactory.handler(serviceId, method, path, outputSchema != null);
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
                        STRING_TYPE,
                        "const",
                        method.name(),
                        DESCRIPTION,
                        "HTTP method for the underlying API call"));
        properties.put(
                "path", Map.of("type", STRING_TYPE, "const", path, DESCRIPTION, "Path template for the API call"));
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

    /**
     * Best-effort MCP {@code outputSchema} describing the operation's success (2xx) response, or
     * {@code null} when that response is not a JSON object (arrays, scalars, or no body) — those tools
     * stay text-only and carry no output schema.
     *
     * <p>The emitted schema is deliberately <strong>permissive and self-contained</strong>:
     * {@code type:object}, {@code additionalProperties:true}, no {@code required}, and each property
     * entry carries a {@code description} only — never a {@code type} or a {@code $ref}. This is a
     * hard requirement, not a stylistic choice: the MCP async server validates every successful
     * result's {@code structuredContent} against this exact map with <em>no</em> access to
     * {@code #/components}, so any {@code $ref} we emitted would be unresolvable and any strict type
     * or {@code required} constraint would turn a legitimate (possibly null-bearing) backend response
     * into a validation error and fail the tool call. Field names + descriptions give clients the
     * response shape while guaranteeing any JSON object validates. The proxy pairs this with an object
     * {@code structuredContent} on success (see {@link OperationProxyFactory}).
     */
    @Nullable
    Map<String, Object> buildOutputSchema(@NonNull OpenAPI openApi, @NonNull Operation operation) {
        Schema<?> responseSchema = successResponseSchema(operation);
        if (responseSchema == null) {
            return null;
        }
        Schema<?> resolved = resolveRef(openApi, responseSchema);
        if (resolved == null || !isObjectSchema(resolved)) {
            return null;
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", OBJECT);
        String title = refName(responseSchema);
        if (title != null) {
            schema.put("title", title);
        }
        if (StringUtils.hasText(resolved.getDescription())) {
            schema.put(DESCRIPTION, resolved.getDescription());
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Schema> resolvedProperties = resolved.getProperties();
        if (resolvedProperties != null) {
            resolvedProperties.forEach((name, propSchema) -> {
                Map<String, Object> property = new LinkedHashMap<>();
                property.put(
                        DESCRIPTION,
                        propSchema != null && StringUtils.hasText(propSchema.getDescription())
                                ? propSchema.getDescription()
                                : "Field " + name);
                properties.put(name, property);
            });
        }
        schema.put("properties", properties);
        // Never constrain: any JSON object (including {}) must validate so a real response is never
        // rewritten into an error by the async server's structured-output validation.
        schema.put("additionalProperties", Boolean.TRUE);
        return schema;
    }

    /**
     * The {@code application/json} response schema of the operation's first success status (200, then
     * 201/202, then any {@code 2xx}), or null when none declares a JSON body.
     */
    @Nullable
    private Schema<?> successResponseSchema(@NonNull Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return null;
        }
        ApiResponse response = firstSuccessResponse(responses);
        if (response == null || response.getContent() == null) {
            return null;
        }
        MediaType media = response.getContent().get("application/json");
        if (media == null) {
            media = response.getContent().values().stream().findFirst().orElse(null);
        }
        return media == null ? null : media.getSchema();
    }

    @Nullable
    private ApiResponse firstSuccessResponse(@NonNull ApiResponses responses) {
        for (String code : List.of("200", "201", "202")) {
            ApiResponse response = responses.get(code);
            if (response != null) {
                return response;
            }
        }
        for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
            String code = entry.getKey();
            if (code != null && code.length() == 3 && code.charAt(0) == '2') {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Resolves a single {@code #/components/schemas/*} reference; returns the schema itself if inline. */
    @Nullable
    private Schema<?> resolveRef(@NonNull OpenAPI openApi, @NonNull Schema<?> schema) {
        String name = refName(schema);
        if (name == null) {
            return schema;
        }
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return null;
        }
        return openApi.getComponents().getSchemas().get(name);
    }

    @Nullable
    private static String refName(@NonNull Schema<?> schema) {
        String ref = schema.get$ref();
        if (ref == null) {
            return null;
        }
        int slash = ref.lastIndexOf('/');
        return slash >= 0 ? ref.substring(slash + 1) : ref;
    }

    private static boolean isObjectSchema(@NonNull Schema<?> schema) {
        if (OBJECT.equals(schema.getType())) {
            return true;
        }
        if (schema.getType() != null) {
            return false; // array / string / number / boolean / integer
        }
        // Type absent (common for composed/$ref-derived DTOs): treat as an object only when it
        // actually declares properties.
        return schema.getProperties() != null && !schema.getProperties().isEmpty();
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
