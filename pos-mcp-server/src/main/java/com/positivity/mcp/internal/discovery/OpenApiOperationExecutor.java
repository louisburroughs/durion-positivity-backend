package com.positivity.mcp.internal.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;

/**
 * Bridges an OpenAPI-discovered operation to a synchronous Spring AI tool callback (Gate 3).
 * Built per request by {@link OpenApiToolProvider} from a {@link DiscoveredOperation}'s persisted
 * execution coordinates; invokes the reactive {@link OperationProxyFactory} handler and blocks for
 * the result. Failures render as a controlled error string — never a fabricated success.
 */
public class OpenApiOperationExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiOperationExecutor.class);

    private static final String AUTHORIZATION = "Authorization";
    private static final String HEADERS_ARG = "headers";

    private final OperationProxyFactory proxyFactory;
    private final DiscoveredOperation operation;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final @Nullable String authHeader;

    public OpenApiOperationExecutor(
            @NonNull OperationProxyFactory proxyFactory,
            @NonNull DiscoveredOperation operation,
            @NonNull ObjectMapper objectMapper,
            @NonNull Duration timeout,
            @Nullable String authHeader) {
        this.proxyFactory = proxyFactory;
        this.operation = operation;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        this.authHeader = authHeader;
    }

    public String execute(@Nullable String argumentsJson) {
        try {
            Map<String, Object> arguments = withRelayedAuth(parseArguments(argumentsJson));
            McpSchema.CallToolRequest call = new McpSchema.CallToolRequest(operation.name(), arguments);
            HttpMethod method = HttpMethod.valueOf(operation.httpMethod());
            // Route via the gateway base URI (service_id holds it), not the load-balancer: alpha's
            // Eureka registry is empty, so loadBalancerClient.choose(serviceId) resolves nothing.
            // Facade tools reach the gateway by base URL too. See gate3-openapi-bridge-design.md.
            URI gatewayBaseUri =
                    URI.create(Objects.requireNonNull(operation.serviceId(), "openapi op missing service_id"));
            McpSchema.CallToolResult result = proxyFactory
                    .handlerForBaseUri(
                            gatewayBaseUri,
                            method,
                            Objects.requireNonNull(operation.httpPath(), "openapi op missing http_path"))
                    .apply(null, call)
                    .block(timeout);
            return render(result);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "MCP openapi tool execution failed name={} error={}",
                    operation.name(),
                    exception.getClass().getSimpleName(),
                    exception);
            // The string IS the tool result the model reads, so it must carry something the model
            // can act on (#1711). The class name alone told it nothing: "IllegalArgumentException"
            // is not a fact it can correct an argument from, while "Unsupported period 'Q2-2026':
            // pass YYYY-MM or YYYY" is. Control flow is deliberately unchanged — this path renders
            // rather than throws, and the message is the whole of what was missing.
            String detail = rootMessage(exception);
            return "Error: tool execution failed (" + exception.getClass().getSimpleName() + ")"
                    + (detail.isBlank() ? "" : ": " + detail);
        }
    }

    /**
     * Relays the caller's {@code Authorization} header into the outbound call so the gateway executes
     * the discovered op as the caller (facade tools relay the bearer token the same way). An explicit
     * {@code headers.Authorization} in the tool arguments is left untouched.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withRelayedAuth(@NonNull Map<String, Object> arguments) {
        if (authHeader == null || authHeader.isBlank()) {
            return arguments;
        }
        Map<String, Object> merged = new HashMap<>(arguments);
        Object existing = merged.get(HEADERS_ARG);
        Map<String, Object> headers =
                existing instanceof Map<?, ?> map ? new HashMap<>((Map<String, Object>) map) : new HashMap<>();
        headers.putIfAbsent(AUTHORIZATION, authHeader);
        merged.put(HEADERS_ARG, headers);
        return merged;
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

    /** The deepest non-blank message in the cause chain — the part a model can act on (#1711). */
    private static String rootMessage(@NonNull Throwable thrown) {
        String message = "";
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return message;
    }
}
