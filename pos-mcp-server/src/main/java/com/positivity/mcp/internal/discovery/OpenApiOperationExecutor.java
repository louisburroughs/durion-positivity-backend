package com.positivity.mcp.internal.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;

/**
 * Bridges an OpenAPI-discovered operation to a synchronous LangChain4j {@link ToolExecutor} (Gate 3).
 * Built per request by {@link OpenApiToolProvider} from a {@link DiscoveredOperation}'s persisted
 * execution coordinates; invokes the reactive {@link OperationProxyFactory} handler and blocks for
 * the result. Failures render as a controlled error string — never a fabricated success.
 */
public class OpenApiOperationExecutor implements ToolExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiOperationExecutor.class);

    private final OperationProxyFactory proxyFactory;
    private final DiscoveredOperation operation;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public OpenApiOperationExecutor(
            @NonNull OperationProxyFactory proxyFactory,
            @NonNull DiscoveredOperation operation,
            @NonNull ObjectMapper objectMapper,
            @NonNull Duration timeout) {
        this.proxyFactory = proxyFactory;
        this.operation = operation;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    @Override
    public String execute(@NonNull ToolExecutionRequest request, @Nullable Object memoryId) {
        try {
            Map<String, Object> arguments = parseArguments(request.arguments());
            McpSchema.CallToolRequest call = new McpSchema.CallToolRequest(operation.name(), arguments);
            HttpMethod method = HttpMethod.valueOf(operation.httpMethod());
            McpSchema.CallToolResult result = proxyFactory
                    .handler(operation.serviceId(), method, operation.httpPath())
                    .apply(null, call)
                    .block(timeout);
            return render(result);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "MCP openapi tool execution failed name={} error={}",
                    operation.name(),
                    exception.getClass().getSimpleName(),
                    exception);
            return "Error: tool execution failed (" + exception.getClass().getSimpleName() + ")";
        }
    }

    private Map<String, Object> parseArguments(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // Do NOT execute with empty/implicit args on parse failure — that risks unintended
            // (esp. write) calls. Fail the tool call; execute() renders this as a controlled error.
            LOGGER.warn("MCP openapi tool args parse failed name={}; failing the call", operation.name());
            throw new IllegalArgumentException("invalid tool arguments");
        }
    }

    private String render(McpSchema.@Nullable CallToolResult result) {
        if (result == null) {
            return "Error: no response from operation";
        }
        String text = result.content() == null
                ? ""
                : result.content().stream()
                        .filter(c -> c instanceof McpSchema.TextContent)
                        .map(c -> ((McpSchema.TextContent) c).text())
                        .collect(Collectors.joining("\n"));
        return Boolean.TRUE.equals(result.isError()) ? "Error: " + text : text;
    }
}
