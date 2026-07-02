package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.discovery.OpenApiOperationExecutor;
import com.positivity.mcp.internal.discovery.OperationProxyFactory;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.service.CurrentUserContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LangChain4j {@link ToolProvider} that exposes permission-eligible OpenAPI-discovered operations
 * as agent-callable tools (Gate 3).
 *
 * <p><strong>Leakage prevention:</strong> {@link #provideTools} is invoked per request
 * ({@link #isDynamic()} is true) and reads the current caller from {@link RequestScopedUserContext}.
 * Permissions are never captured at agent-build time, so a cached agent cannot expose a prior,
 * higher-permission caller's tools. If no request context is present (e.g. the streaming/Reactor
 * path, whose context propagation is not yet wired), it returns <em>no</em> tools — fail-closed.
 */
@Component
public class OpenApiToolProvider implements ToolProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiToolProvider.class);

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}/]+)}");

    private static final JsonObjectSchema QUERY_PARAMS_SCHEMA =
            JsonObjectSchema.builder().description("Query-string parameters.").build();
    private static final JsonObjectSchema HEADERS_SCHEMA = JsonObjectSchema.builder()
            .description("Extra HTTP headers (auth is relayed automatically).")
            .build();
    private static final JsonObjectSchema BODY_SCHEMA = JsonObjectSchema.builder()
            .description("Request body payload, for write operations.")
            .build();

    /**
     * The request envelope {@link OperationProxyFactory} reads. Path parameters are typed from the
     * operation's path template ({@code /v1/products/{productId}} → a required string
     * {@code productId}), so the model knows exactly which path values to supply; query/header/body
     * stay free-form objects. Method/path themselves are fixed from the persisted coordinates.
     */
    private JsonObjectSchema buildParameterSchema(@NonNull DiscoveredOperation op) {
        List<String> pathParams = extractPathParams(op.httpPath());
        JsonObjectSchema.Builder pathParamsSchema = JsonObjectSchema.builder().description("Path template parameters.");
        for (String name : pathParams) {
            pathParamsSchema.addStringProperty(name, "Path parameter " + name);
        }
        if (!pathParams.isEmpty()) {
            pathParamsSchema.required(pathParams);
        }
        return JsonObjectSchema.builder()
                .description("Parameters for the gateway operation named in the tool description.")
                .addProperty("pathParams", pathParamsSchema.build())
                .addProperty("queryParams", buildQueryParamsSchema(op.inputSchema()))
                .addProperty("headers", HEADERS_SCHEMA)
                .addProperty("body", BODY_SCHEMA)
                .build();
    }

    /**
     * Builds a typed {@code queryParams} schema from the operation's persisted query-parameter JSON
     * ({@code {"query":[{"name","type","required"}]}}). Falls back to a free-form object when the
     * operation has no persisted query params or the JSON cannot be parsed.
     */
    private JsonObjectSchema buildQueryParamsSchema(@Nullable String inputSchemaJson) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            return QUERY_PARAMS_SCHEMA;
        }
        try {
            JsonNode query = objectMapper.readTree(inputSchemaJson).get("query");
            if (query == null || !query.isArray() || query.isEmpty()) {
                return QUERY_PARAMS_SCHEMA;
            }
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder().description("Query-string parameters.");
            List<String> required = new ArrayList<>();
            for (JsonNode param : query) {
                String name = param.path("name").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                String description = "Query parameter " + name;
                switch (param.path("type").asText("string")) {
                    case "integer" -> builder.addIntegerProperty(name, description);
                    case "number" -> builder.addNumberProperty(name, description);
                    case "boolean" -> builder.addBooleanProperty(name, description);
                    default -> builder.addStringProperty(name, description);
                }
                if (param.path("required").asBoolean(false)) {
                    required.add(name);
                }
            }
            if (!required.isEmpty()) {
                builder.required(required);
            }
            return builder.build();
        } catch (JsonProcessingException | RuntimeException e) {
            LOGGER.debug("MCP openapi query-param schema parse failed; using free-form object: {}", e.getMessage());
            return QUERY_PARAMS_SCHEMA;
        }
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

    public OpenApiToolProvider(
            @NonNull ToolMetadataRepository repository,
            @NonNull EmbeddingModel embeddingModel,
            @NonNull RequestScopedUserContext userContext,
            @NonNull OperationProxyFactory proxyFactory,
            @NonNull ObjectMapper objectMapper,
            @Value("${mcp.agent.candidate-tool-limit:8}") int candidateLimit,
            @Value("${mcp.openapi.tool.timeout:30s}") @NonNull Duration executionTimeout) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.userContext = userContext;
        this.proxyFactory = proxyFactory;
        this.objectMapper = objectMapper;
        this.candidateLimit = Math.max(1, candidateLimit);
        this.executionTimeout = executionTimeout;
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        Optional<CurrentUserContext> maybe = userContext.current();
        if (maybe.isEmpty()) {
            LOGGER.debug("MCP openapi tool provider: no request-scoped user context; no tools (fail-closed)");
            return new ToolProviderResult(Map.of());
        }
        CurrentUserContext caller = maybe.get();
        String authHeader = userContext.currentAuthHeader().orElse(null);
        String userMessage =
                request.userMessage() == null ? "" : request.userMessage().singleText();
        if (userMessage == null || userMessage.isBlank()) {
            return new ToolProviderResult(Map.of());
        }

        float[] embedding = embeddingModel.embed(userMessage).content().vector();
        // Workflow state defaults to IDLE here; the session-owned state is threaded on the
        // NLTI-session path (Gate 2C). Permission gating still applies inside the query.
        List<DiscoveredOperation> ops = repository.findDiscoveredCandidatesForPermissions(
                embedding, candidateLimit, caller.permissionCodes(), WorkflowState.DEFAULT.name());

        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        for (DiscoveredOperation op : ops) {
            if (!op.isExecutable()) {
                LOGGER.debug("MCP openapi op missing execution coordinates name={}; skipping", op.name());
                continue;
            }
            ToolSpecification spec = ToolSpecification.builder()
                    .name(op.name())
                    .description(describeOperation(op))
                    .parameters(buildParameterSchema(op))
                    .build();
            tools.put(spec, new OpenApiOperationExecutor(proxyFactory, op, objectMapper, executionTimeout, authHeader));
        }
        userContext.recordDiscoveredOpenapiTools(
                tools.keySet().stream().map(ToolSpecification::name).toList());
        LOGGER.debug(
                "MCP openapi tool provider role={} permissionCount={} discoveredTools={}",
                caller.primaryRole(),
                caller.permissionCodes().size(),
                tools.size());
        return new ToolProviderResult(tools);
    }
}
