package com.positivity.mcp.internal.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@Component
public class OperationProxyFactory {

    private static final Logger log = LoggerFactory.getLogger(OperationProxyFactory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LoadBalancerClient loadBalancerClient;
    private final WebClient webClient;

    OperationProxyFactory(@NonNull LoadBalancerClient loadBalancerClient, @NonNull WebClient discoveryWebClient) {
        this.loadBalancerClient = loadBalancerClient;
        this.webClient = discoveryWebClient;
    }

    @NonNull
    BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handler(
            @NonNull String serviceId, @NonNull HttpMethod method, @NonNull String pathTemplate) {
        return handler(serviceId, method, pathTemplate, false);
    }

    /**
     * @param structuredOutput when true the tool declared an MCP {@code outputSchema}, so a successful
     *     result must also carry an object {@code structuredContent} (see {@link #successResult}).
     */
    @NonNull
    BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handler(
            @NonNull String serviceId,
            @NonNull HttpMethod method,
            @NonNull String pathTemplate,
            boolean structuredOutput) {
        return (exchange, request) -> {
            var arguments = Optional.ofNullable(request.arguments()).orElse(Map.of());
            Map<String, Object> pathParams = asMap(arguments.get("pathParams"));
            Map<String, Object> queryParams = asMap(arguments.get("queryParams"));
            Map<String, Object> headers = asMap(arguments.get("headers"));
            Object body = arguments.get("body");

            // LoadBalancerClient.choose() is used here because the service ID is dynamic —
            // resolved at
            // runtime from the OpenAPI discovery registry. A static @LoadBalanced
            // RestClient base URL
            // cannot be pre-built at construction time when the target service ID is
            // unknown until the
            // request arrives. Facade tools (e.g. AccountingFacadeTool, CatalogFacadeTool)
            // use
            // @LoadBalanced RestClient.Builder instead because their service ID and base
            // URL are static
            // and known at startup. Rule: prefer @LoadBalanced RestClient for new
            // static-target clients;
            // use LoadBalancerClient.choose() only when the service ID must be resolved
            // dynamically per request.
            ServiceInstance instance = loadBalancerClient.choose(serviceId);
            if (instance == null) {
                return Mono.just(errorResult("No instance available for service " + serviceId));
            }

            URI targetUri = buildUriFromBase(instance.getUri(), pathTemplate, pathParams, queryParams);
            return executeRequest(method, targetUri, headers, body, serviceId + " " + pathTemplate, structuredOutput);
        };
    }

    /**
     * Returns a handler that invokes the given gateway base URI directly, bypassing load-balancer
     * resolution. Used for aggregate-discovered tools where the gateway base URI is known at
     * mapping time and does not need per-request service lookup.
     */
    @NonNull
    BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handlerForBaseUri(
            @NonNull URI baseUri, @NonNull HttpMethod method, @NonNull String pathTemplate) {
        return handlerForBaseUri(baseUri, method, pathTemplate, false);
    }

    /**
     * @param structuredOutput when true the tool declared an MCP {@code outputSchema}, so a successful
     *     result must also carry an object {@code structuredContent} (see {@link #successResult}).
     */
    @NonNull
    BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handlerForBaseUri(
            @NonNull URI baseUri, @NonNull HttpMethod method, @NonNull String pathTemplate, boolean structuredOutput) {
        return (exchange, request) -> {
            var arguments = Optional.ofNullable(request.arguments()).orElse(Map.of());
            Map<String, Object> pathParams = asMap(arguments.get("pathParams"));
            Map<String, Object> queryParams = asMap(arguments.get("queryParams"));
            Map<String, Object> headers = asMap(arguments.get("headers"));
            Object body = arguments.get("body");

            URI targetUri = buildUriFromBase(baseUri, pathTemplate, pathParams, queryParams);
            return executeRequest(method, targetUri, headers, body, baseUri + " " + pathTemplate, structuredOutput);
        };
    }

    private Mono<McpSchema.CallToolResult> executeRequest(
            @NonNull HttpMethod method,
            @NonNull URI targetUri,
            @NonNull Map<String, Object> headers,
            Object body,
            @NonNull String logContext,
            boolean structuredOutput) {
        var requestSpec = webClient.method(method).uri(targetUri);
        headers.forEach((key, value) -> requestSpec.header(key, String.valueOf(value)));
        if (body != null) {
            requestSpec.contentType(MediaType.APPLICATION_JSON);
        }
        if (log.isDebugEnabled()) {
            log.debug("Tool proxy calling {} {} ({})", method, targetUri, logContext);
        }
        return requestSpec
                .body(body != null ? BodyInserters.fromValue(body) : BodyInserters.empty())
                .retrieve()
                .toEntity(String.class)
                .map(responseEntity -> {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "Tool proxy {} {} -> {}",
                                method,
                                targetUri,
                                responseEntity.getStatusCode().value());
                    }
                    return successResult(responseEntity.getBody(), structuredOutput);
                })
                .onErrorResume(ex -> {
                    log.warn("Tool proxy failed for {} {}: {}", logContext, targetUri, ex.getMessage());
                    return Mono.just(errorResult(ex.getMessage()));
                });
    }

    private URI buildUriFromBase(
            @NonNull URI baseUri,
            @NonNull String pathTemplate,
            @NonNull Map<String, Object> pathParams,
            @NonNull Map<String, Object> queryParams) {
        String resolvedPath = resolvePath(pathTemplate, pathParams);
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(baseUri).path(resolvedPath);
        queryParams.forEach((key, value) -> {
            if (value instanceof List<?> list) {
                list.forEach(item -> builder.queryParam(key, item));
            } else {
                builder.queryParam(key, value);
            }
        });
        return builder.build(true).toUri();
    }

    private String resolvePath(@NonNull String template, @NonNull Map<String, Object> pathParams) {
        String resolved = template;
        for (var entry : pathParams.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return resolved;
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        return Collections.emptyMap();
    }

    /**
     * Builds a non-error result carrying the raw response body as text. When {@code structuredOutput}
     * is true (the tool declared an MCP {@code outputSchema}) it also attaches an object
     * {@code structuredContent}: the parsed JSON body when it is an object, otherwise an empty object.
     * The empty-object fallback is required because the async server validates structuredContent
     * against the (object) outputSchema on every successful result and rewrites it to an error if it
     * is missing or non-conforming — the full body is always still available in the text block.
     */
    private McpSchema.CallToolResult successResult(@Nullable String body, boolean structuredOutput) {
        var builder = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(body == null ? "" : body)))
                .isError(false);
        if (structuredOutput) {
            builder.structuredContent(parseStructuredContent(body));
        }
        return builder.build();
    }

    /**
     * Parses a JSON object response body for MCP {@code structuredContent}. Returns an empty object
     * for a null/blank/non-object/unparseable body so structured-output validation always passes.
     */
    @NonNull
    static Map<String, Object> parseStructuredContent(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node != null && node.isObject()) {
                Map<String, Object> map = MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {});
                return map == null ? Map.of() : map;
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.debug("Structured content parse failed; emitting empty object: {}", e.getMessage());
        }
        return Map.of();
    }

    private McpSchema.CallToolResult errorResult(@NonNull String message) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("Error: " + message)))
                .isError(true)
                .build();
    }
}
