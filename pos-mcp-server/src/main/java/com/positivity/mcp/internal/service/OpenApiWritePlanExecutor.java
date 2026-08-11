package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.discovery.OpenApiOperationExecutor;
import com.positivity.mcp.internal.discovery.OperationProxyFactory;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.exception.WritePlanExecutionException;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gate 6 (#1193): executes a confirmed write plan against an OpenAPI-discovered operation's
 * persisted execution coordinates, reusing the Gate 3 {@link OpenApiOperationExecutor} bridge.
 * The exact persisted {@code argsJson} is passed through verbatim.
 */
@Component
public class OpenApiWritePlanExecutor implements WritePlanExecutor {

    /** The Gate 3 executor renders failures as this prefix rather than throwing. */
    static final String ERROR_PREFIX = "Error:";

    private final ToolMetadataRepository repository;
    private final OperationProxyFactory proxyFactory;
    private final ObjectMapper objectMapper;
    private final Duration executionTimeout;

    public OpenApiWritePlanExecutor(
            @NonNull ToolMetadataRepository repository,
            @NonNull OperationProxyFactory proxyFactory,
            @NonNull ObjectMapper objectMapper,
            @Value("${mcp.openapi.tool.timeout:30s}") @NonNull Duration executionTimeout) {
        this.repository = repository;
        this.proxyFactory = proxyFactory;
        this.objectMapper = objectMapper;
        this.executionTimeout = executionTimeout;
    }

    @Override
    public @NonNull String execute(@NonNull String targetTool, @NonNull String argsJson, @Nullable String authHeader) {
        DiscoveredOperation operation = repository
                .findDiscoveredOperationByName(targetTool)
                .orElseThrow(() -> new WritePlanExecutionException(
                        "No discovered operation with execution coordinates for tool: " + targetTool));
        if (!operation.isExecutable()) {
            throw new WritePlanExecutionException(
                    "Discovered operation is missing execution coordinates: " + targetTool);
        }
        String result = new OpenApiOperationExecutor(
                        proxyFactory, operation, objectMapper, executionTimeout, authHeader)
                .execute(argsJson);
        if (result == null) {
            throw new WritePlanExecutionException("Write-plan tool execution returned no result for " + targetTool);
        }
        if (result.startsWith(ERROR_PREFIX)) {
            throw new WritePlanExecutionException("Write-plan tool execution failed for " + targetTool + ": " + result);
        }
        return result;
    }
}
