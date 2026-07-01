package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.discovery.OpenApiOperationExecutor;
import com.positivity.mcp.internal.discovery.OperationProxyFactory;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.service.CurrentUserContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
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
                    .description(op.description())
                    .build();
            tools.put(spec, new OpenApiOperationExecutor(proxyFactory, op, objectMapper, executionTimeout, authHeader));
        }
        LOGGER.debug(
                "MCP openapi tool provider role={} permissionCount={} discoveredTools={}",
                caller.primaryRole(),
                caller.permissionCodes().size(),
                tools.size());
        return new ToolProviderResult(tools);
    }
}
