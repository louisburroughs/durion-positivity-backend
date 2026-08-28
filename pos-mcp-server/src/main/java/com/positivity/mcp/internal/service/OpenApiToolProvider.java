package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.discovery.OpenApiOperationExecutor;
import com.positivity.mcp.internal.discovery.OperationProxyFactory;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring AI callback resolver that exposes permission-eligible OpenAPI-discovered operations
 * as agent-callable tools (Gate 3).
 *
 * <p><strong>Leakage prevention:</strong> {@link #resolveToolCallbacks(String)} is invoked per request
 * and reads the current caller from {@link RequestScopedUserContext}.
 * Permissions are never captured at agent-build time, so a cached agent cannot expose a prior,
 * higher-permission caller's tools. Both the synchronous and streaming managers publish the caller on
 * the calling thread before tool resolution runs (the streaming assistant resolves callbacks
 * synchronously at Flux-assembly time, while the context is still set), and clear it afterwards. If no
 * request context is present, this returns <em>no</em> tools — fail-closed.
 */
@Component
public class OpenApiToolProvider {
    private static final String STRING_TYPE = "string";

    private static final String REQUIRED = "required";

    private static final String PROPERTIES = "properties";

    private static final String QUERY_STRING_PARAMETERS_DESCRIPTION = "Query-string parameters.";

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiToolProvider.class);

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}/]+)}");

    /**
     * The request envelope {@link OperationProxyFactory} reads. Path parameters are typed from the
     * operation's path template ({@code /v1/products/{productId}} → a required string
     * {@code productId}), so the model knows exactly which path values to supply. Query parameters are
     * typed from the persisted {@code input_schema}; headers/body stay free-form objects. Method/path
     * themselves are fixed from the persisted coordinates.
     */
    private String buildParameterSchema(@NonNull DiscoveredOperation op) {
        List<String> pathParams = extractPathParams(op.httpPath());
        Map<String, Object> pathParamsSchema = schemaObject("Path template parameters.");
        @SuppressWarnings("unchecked")
        Map<String, Object> pathProperties = (Map<String, Object>) pathParamsSchema.get(PROPERTIES);
        for (String name : pathParams) {
            pathProperties.put(name, schemaProperty(STRING_TYPE, "Path parameter " + name));
        }
        if (!pathParams.isEmpty()) {
            pathParamsSchema.put(REQUIRED, pathParams);
        }
        Map<String, Object> schema =
                schemaObject("Parameters for the gateway operation named in the tool description.");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get(PROPERTIES);
        properties.put("pathParams", pathParamsSchema);
        properties.put("queryParams", buildQueryParamsSchema(op.inputSchema()));
        properties.put("headers", schemaObject("Extra HTTP headers (auth is relayed automatically)."));
        properties.put("body", schemaObject("Request body payload, for write operations."));
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize OpenAPI tool schema for " + op.name(), e);
        }
    }

    /**
     * Builds a typed {@code queryParams} schema from the operation's persisted query-parameter JSON
     * ({@code {"query":[{"name","type","required"}]}}). Falls back to a free-form object when the
     * operation has no persisted query params or the JSON cannot be parsed.
     */
    private Map<String, Object> buildQueryParamsSchema(@Nullable String inputSchemaJson) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            return schemaObject(QUERY_STRING_PARAMETERS_DESCRIPTION);
        }
        try {
            JsonNode query = objectMapper.readTree(inputSchemaJson).get("query");
            if (query == null || !query.isArray() || query.isEmpty()) {
                return schemaObject(QUERY_STRING_PARAMETERS_DESCRIPTION);
            }
            Map<String, Object> builder = schemaObject(QUERY_STRING_PARAMETERS_DESCRIPTION);
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) builder.get(PROPERTIES);
            List<String> required = new ArrayList<>();
            for (JsonNode param : query) {
                String name = param.path("name").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                String description = "Query parameter " + name;
                properties.put(name, schemaProperty(param.path("type").asText(STRING_TYPE), description));
                if (param.path(REQUIRED).asBoolean(false)) {
                    required.add(name);
                }
            }
            if (!required.isEmpty()) {
                builder.put(REQUIRED, required);
            }
            return builder;
        } catch (JsonProcessingException | RuntimeException e) {
            LOGGER.debug("MCP openapi query-param schema parse failed; using free-form object", e);
            return schemaObject(QUERY_STRING_PARAMETERS_DESCRIPTION);
        }
    }

    private static @NonNull Map<String, Object> schemaObject(@NonNull String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", description);
        schema.put(PROPERTIES, new LinkedHashMap<String, Object>());
        return schema;
    }

    private static @NonNull Map<String, Object> schemaProperty(@NonNull String type, @NonNull String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put(
                "type",
                switch (type) {
                    case "integer" -> "integer";
                    case "number" -> "number";
                    case "boolean" -> "boolean";
                    default -> STRING_TYPE;
                });
        property.put("description", description);
        return property;
    }

    private static List<String> extractPathParams(@Nullable String path) {
        if (path == null) {
            return List.of();
        }
        List<String> params = new ArrayList<>();
        Matcher matcher = PATH_PARAM_PATTERN.matcher(path);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!params.contains(name)) {
                params.add(name);
            }
        }
        return params;
    }

    /** A discovered operation is write-capable when it executes with a non-GET HTTP method. */
    private static boolean isWriteCapable(@NonNull DiscoveredOperation op) {
        String method = op.httpMethod();
        return method != null && !"GET".equalsIgnoreCase(method.trim());
    }

    private static String describeOperation(@NonNull DiscoveredOperation op) {
        return op.description() + " [" + op.httpMethod() + " " + op.httpPath() + "]";
    }

    private final ToolMetadataRepository repository;
    private final EmbeddingModel embeddingModel;
    private final RequestScopedUserContext userContext;
    private final OperationProxyFactory proxyFactory;
    private final ObjectMapper objectMapper;
    private final int candidateLimit;
    private final Duration executionTimeout;
    private final @Nullable ToolInvocationRecorder invocationRecorder;

    public OpenApiToolProvider(
            @NonNull ToolMetadataRepository repository,
            @NonNull EmbeddingModel embeddingModel,
            @NonNull RequestScopedUserContext userContext,
            @NonNull OperationProxyFactory proxyFactory,
            @NonNull ObjectMapper objectMapper,
            @Value("${mcp.agent.candidate-tool-limit:8}") int candidateLimit,
            @Value("${mcp.openapi.tool.timeout:30s}") @NonNull Duration executionTimeout,
            @Nullable ToolInvocationRecorder invocationRecorder) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.userContext = userContext;
        this.proxyFactory = proxyFactory;
        this.objectMapper = objectMapper;
        this.candidateLimit = Math.max(1, candidateLimit);
        this.executionTimeout = executionTimeout;
        this.invocationRecorder = invocationRecorder;
    }

    public @NonNull List<ToolCallback> resolveToolCallbacks(@Nullable String userMessage) {
        Optional<CurrentUserContext> maybe = userContext.current();
        if (maybe.isEmpty()) {
            LOGGER.debug("MCP openapi tool provider: no request-scoped user context; no tools (fail-closed)");
            return List.of();
        }
        CurrentUserContext caller = maybe.get();
        String authHeader = userContext.currentAuthHeader().orElse(null);
        if (userMessage == null || userMessage.isBlank()) {
            return List.of();
        }

        float[] embedding = embeddingModel.embed(userMessage);
        // Workflow state defaults to IDLE here; the session-owned state is threaded on the
        // NLTI-session path (Gate 2C). Permission gating still applies inside the query.
        List<DiscoveredOperation> ops = repository.findDiscoveredCandidatesForPermissions(
                embedding, candidateLimit, caller.permissionCodes(), WorkflowState.DEFAULT.name());

        List<ToolCallback> tools = new ArrayList<>();
        boolean writeCapableToolsPresent = false;
        for (DiscoveredOperation op : ops) {
            if (!op.isExecutable()) {
                LOGGER.debug("MCP openapi op missing execution coordinates name={}; skipping", op.name());
                continue;
            }
            writeCapableToolsPresent = writeCapableToolsPresent || isWriteCapable(op);
            OpenApiOperationExecutor executor =
                    new OpenApiOperationExecutor(proxyFactory, op, objectMapper, executionTimeout, authHeader);
            ToolCallback callback = new OpenApiSpringAiToolCallback(
                    DefaultToolDefinition.builder()
                            .name(op.name())
                            .description(describeOperation(op))
                            .inputSchema(buildParameterSchema(op))
                            .build(),
                    executor);
            // #1422: per-execution invocation logging; discovered names match mcp_tool.name exactly.
            tools.add(invocationRecorder != null ? invocationRecorder.wrap(callback, op.name()) : callback);
        }
        userContext.recordDiscoveredOpenapiTools(tools.stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList());
        // #1193: recorded BEFORE the assistant assembles the system prompt (tools are resolved
        // first), so the per-request prompt supplier can append the WRITE-GATE layer exactly when a
        // write-capable tool is in this request's candidate set. Facade tools are read-only (GET
        // lookups), so discovered non-GET operations are the only write-capable candidates.
        userContext.recordWriteCapableToolsPresent(writeCapableToolsPresent);
        LOGGER.debug(
                "MCP openapi tool provider role={} permissionCount={} discoveredTools={}",
                caller.primaryRole(),
                caller.permissionCodes().size(),
                tools.size());
        return List.copyOf(tools);
    }

    private static final class OpenApiSpringAiToolCallback implements ToolCallback {

        private final ToolDefinition toolDefinition;
        private final OpenApiOperationExecutor executor;

        private OpenApiSpringAiToolCallback(
                @NonNull ToolDefinition toolDefinition, @NonNull OpenApiOperationExecutor executor) {
            this.toolDefinition = toolDefinition;
            this.executor = executor;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return DefaultToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            return executor.execute(toolInput);
        }
    }
}
