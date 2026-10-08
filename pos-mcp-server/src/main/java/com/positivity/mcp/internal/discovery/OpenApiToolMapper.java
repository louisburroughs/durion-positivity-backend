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
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OpenApiToolMapper {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiToolMapper.class);
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

    /**
     * Maps one service's own OpenAPI (fetched through Eureka by the #645 full fallback and the #1632
     * targeted failed-prefix fallback) to tool specifications named {@code {serviceId}_{operationId}}.
     * Its paths carry no routing prefix ({@code /v1/audit/events}), so the #2370 write exclusion is
     * matched against the path as the gateway routes it: the service's routing prefix, derived from
     * its id by {@link #routingDomain}, prepended ({@code /security-service/v1/audit/events}). Every
     * non-GET operation the aggregate path would drop is dropped here too; GET stays.
     */
    @NonNull
    public List<McpServerFeatures.AsyncToolSpecification> toToolSpecifications(
            @NonNull String serviceId, @NonNull URI baseUri, @NonNull OpenAPI openApi) {
        var specs = new ArrayList<McpServerFeatures.AsyncToolSpecification>();
        if (openApi.getPaths() == null) {
            return specs;
        }

        String routingPrefix = "/" + routingDomain(serviceId);
        openApi.getPaths().forEach((path, pathItem) -> {
            Map<HttpMethod, Operation> operations = withoutRevealOperations(routingPrefix + path, pathItem);
            if (!properties.includesPath(path)) {
                return;
            }
            operations.forEach((method, operation) -> {
                if (properties.excludesWrite(routingPrefix + path, method)) {
                    LOGGER.debug(
                            "Per-service discovery of {} excluded write operation {} {} (#2370: audit/platform-event"
                                    + " writes are never agent tools)",
                            serviceId,
                            method,
                            routingPrefix + path);
                    return;
                }
                addOperation(specs, openApi, serviceId, baseUri, path, operation, method);
            });
        });
        return specs;
    }

    /**
     * The gateway routing prefix, without its leading slash, of a Eureka service id: lower-cased,
     * conventional {@code pos-} prefix stripped ({@code pos-vehicle-fitment} → {@code
     * vehicle-fitment}, {@code SECURITY-SERVICE} → {@code security-service}). It is also the
     * tool-catalog domain the aggregate path persists as {@code mcp_tool.domain}. The targeted
     * failed-prefix fallback already passes the prefix itself, which maps to itself.
     */
    static @NonNull String routingDomain(@NonNull String serviceId) {
        String lower = serviceId.toLowerCase(Locale.ROOT);
        return lower.startsWith("pos-") ? lower.substring(4) : lower;
    }

    /**
     * Maps aggregate OpenAPI operations to tool specifications using the gateway base URI directly.
     * Tool names are derived as {@code {domain}_{operationId}} where the domain is the first
     * non-version path segment (e.g. {@code /v1/accounting/invoices} → {@code accounting}).
     * Paths matching any configured {@code excludedPathFragments} are skipped, and so is every
     * non-GET operation on a path matching {@code excludedWritePathPatterns} (#2370: audit and
     * platform-event writes are never agent tools). The exclusions are logged by
     * {@link #toDiscoveredOperations}, which runs on the same aggregate in the same cycle, so each
     * one appears once per run.
     */
    @NonNull
    public List<McpServerFeatures.AsyncToolSpecification> toAggregateToolSpecifications(
            @NonNull URI gatewayBaseUri, @NonNull OpenAPI openApi) {
        var specs = new ArrayList<McpServerFeatures.AsyncToolSpecification>();
        if (openApi.getPaths() == null) {
            return specs;
        }
        openApi.getPaths().forEach((path, pathItem) -> {
            Map<HttpMethod, Operation> operations = withoutRevealOperations(path, pathItem);
            if (!properties.includesPath(path) || properties.excludesPath(path)) {
                return;
            }
            operations.forEach((method, operation) -> {
                if (properties.excludesWrite(path, method)) {
                    return;
                }
                addAggregateOperation(specs, openApi, gatewayBaseUri, path, operation, method);
            });
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
            Map<HttpMethod, Operation> operations = withoutRevealOperations(path, pathItem);
            if (!properties.includesPath(path) || properties.excludesPath(path)) {
                return;
            }
            operations.forEach((method, operation) -> {
                if (properties.excludesWrite(path, method)) {
                    // #2370: the one log line per excluded operation per discovery run (no body).
                    LOGGER.debug(
                            "Discovery excluded write operation {} {} (#2370: audit/platform-event writes are never"
                                    + " agent tools)",
                            method,
                            path);
                    return;
                }
                addDiscoveredOperation(operations, serviceId, path, operation, method);
            });
        });
        return operations;
    }

    /**
     * #2370: the domains of the operations {@link #toDiscoveredOperations} dropped under {@code
     * excludedWritePathPatterns}. The stale-row prune (#1819) treats a registered domain that
     * contributed no operation this run as unseen and keeps its rows; a domain whose operations were
     * all excluded on purpose was seen, so the caller unions this set into the run's discovered
     * domains and the excluded operations' previously-registered rows are pruned.
     */
    @NonNull
    public Set<String> excludedWriteDomains(@NonNull OpenAPI openApi) {
        Set<String> domains = new LinkedHashSet<>();
        if (openApi.getPaths() == null) {
            return domains;
        }
        openApi.getPaths().forEach((path, pathItem) -> {
            // A domain whose reveal operation was dropped was seen too: its previously-registered row is pruned.
            if (operationsOf(pathItem).entrySet().stream()
                    .anyMatch(entry -> isRevealOperation(path, entry.getValue()))) {
                domains.add(extractDomain(path));
            }
            if (!properties.includesPath(path) || properties.excludesPath(path)) {
                return;
            }
            operationsOf(pathItem).keySet().stream()
                    .filter(method -> properties.excludesWrite(path, method))
                    .findFirst()
                    .ifPresent(method -> domains.add(extractDomain(path)));
        });
        return domains;
    }

    // ── Reveal operations are never tools (#2621; Security ruling on #2617 / #2621, ADR-0072 Decision 4) ──

    /** The permission marker: a {@code <domain>:<resource>:reveal} entry in {@code x-required-permissions}. */
    private static final java.util.regex.Pattern REVEAL_PERMISSION =
            java.util.regex.Pattern.compile("^[a-z_]+:[a-z_]+:reveal$");

    /**
     * Whether an operation returns a RESTRICTED value and so is never an agent tool (ADR-0072 Decision 4, CHK-010).
     * Either marker is enough: an {@code x-required-permissions} entry whose action is {@code reveal}, or a path
     * ending in {@code /reveal}. The action {@code reveal} is reserved for such operations.
     *
     * <p>This is code, deliberately, and not an entry in {@code excludedWritePathPatterns}: a configuration list
     * can be edited or emptied, and a revealed value would enter the model's context, its provider's request, the
     * conversation store and the tool-result logs. It applies to every HTTP method and runs before every include
     * rule, so no configuration can re-include such an operation.
     */
    public static boolean isRevealOperation(@NonNull String path, @NonNull Operation operation) {
        return hasRevealPath(path) || hasRevealPermission(operation);
    }

    /** The path marker: the path, with or without a routing prefix, ends in {@code /reveal}. */
    public static boolean hasRevealPath(@NonNull String path) {
        return path.endsWith("/reveal");
    }

    /** The permission marker: an {@code x-required-permissions} entry whose action is {@code reveal}. */
    public static boolean hasRevealPermission(@NonNull Operation operation) {
        return extractRequiredPermissions(operation).stream()
                .anyMatch(code -> REVEAL_PERMISSION.matcher(code).matches());
    }

    /** The path item's operations minus every reveal operation, each logged once (no body, no value). */
    private static @NonNull Map<HttpMethod, Operation> withoutRevealOperations(
            @NonNull String path, @NonNull PathItem pathItem) {
        Map<HttpMethod, Operation> operations = operationsOf(pathItem);
        operations.entrySet().removeIf(entry -> {
            if (!isRevealOperation(path, entry.getValue())) {
                return false;
            }
            LOGGER.debug(
                    "Discovery excluded reveal operation {} {} (ADR-0072 Decision 4: an operation returning a"
                            + " RESTRICTED value is never an agent tool)",
                    entry.getKey(),
                    path);
            return true;
        });
        return operations;
    }

    /** The operations a path item declares, in the order discovery has always visited them. */
    private static @NonNull Map<HttpMethod, Operation> operationsOf(@NonNull PathItem pathItem) {
        Map<HttpMethod, Operation> operations = new LinkedHashMap<>();
        putIfPresent(operations, HttpMethod.GET, pathItem.getGet());
        putIfPresent(operations, HttpMethod.POST, pathItem.getPost());
        putIfPresent(operations, HttpMethod.PUT, pathItem.getPut());
        putIfPresent(operations, HttpMethod.DELETE, pathItem.getDelete());
        putIfPresent(operations, HttpMethod.PATCH, pathItem.getPatch());
        return operations;
    }

    private static void putIfPresent(
            @NonNull Map<HttpMethod, Operation> operations, @NonNull HttpMethod method, @Nullable Operation operation) {
        if (operation != null) {
            operations.put(method, operation);
        }
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
        String operationId = buildOperationId(operation);
        String toolName = discoveredToolName(path, operationId);
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
        String operationId = buildOperationId(operation);
        String toolName = discoveredToolName(path, operationId);
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

    /**
     * The name an aggregate operation is registered and persisted under: {@code {domain}_{operationId}},
     * sanitized. The one derivation, shared with the scope graph's schema index (ADR-0069), which
     * must key an operation's schemas by exactly the name {@code mcp_tool.name} holds.
     */
    public static @NonNull String discoveredToolName(@NonNull String path, @NonNull Operation operation) {
        return discoveredToolName(path, buildOperationId(operation));
    }

    private static @NonNull String discoveredToolName(@NonNull String path, @NonNull String operationId) {
        return sanitizeName(extractDomain(path) + "_" + operationId);
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

    private static String sanitizeName(@NonNull String raw) {
        String sanitized = raw.toLowerCase(Locale.US).replaceAll("[^a-z0-9_\\-]", "_");
        if (!StringUtils.hasText(sanitized)) {
            return "tool_" + UUIDv7Generator.generate().toString().replace("-", "");
        }
        return sanitized;
    }

    private static String buildOperationId(@NonNull Operation operation) {
        if (StringUtils.hasText(operation.getOperationId())) {
            return operation.getOperationId();
        }
        if (StringUtils.hasText(operation.getSummary())) {
            return operation.getSummary().replace(' ', '_');
        }
        return "op_" + UUIDv7Generator.generate();
    }
}
