package com.positivity.mcp.internal.discovery;

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

    private final LoadBalancerClient loadBalancerClient;
    private final WebClient webClient;

    OperationProxyFactory(@NonNull LoadBalancerClient loadBalancerClient, @NonNull WebClient discoveryWebClient) {
        this.loadBalancerClient = loadBalancerClient;
        this.webClient = discoveryWebClient;
    }

    @NonNull
    BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handler(
            @NonNull String serviceId, @NonNull HttpMethod method, @NonNull String pathTemplate) {
        return (exchange, request) -> {
            var arguments = Optional.ofNullable(request.arguments()).orElse(Map.of());
            Map<String, Object> pathParams = asMap(arguments.get("pathParams"));
            Map<String, Object> queryParams = asMap(arguments.get("queryParams"));
            Map<String, Object> headers = asMap(arguments.get("headers"));
            Object body = arguments.get("body");

            ServiceInstance instance = loadBalancerClient.choose(serviceId);
            if (instance == null) {
                return Mono.just(errorResult("No instance available for service " + serviceId));
            }

            URI targetUri = buildUri(instance, pathTemplate, pathParams, queryParams);
            var requestSpec = webClient.method(method).uri(targetUri);
            headers.forEach((key, value) -> requestSpec.header(key, String.valueOf(value)));
            if (body != null) {
                requestSpec.contentType(MediaType.APPLICATION_JSON);
            }

            return requestSpec
                    .body(body != null ? BodyInserters.fromValue(body) : BodyInserters.empty())
                    .retrieve()
                    .toEntity(String.class)
                    .map(responseEntity -> successResult(responseEntity.getBody()))
                    .onErrorResume(ex -> {
                        log.warn(
                                "Tool proxy failed for service {} {} {}: {}",
                                serviceId,
                                method,
                                targetUri,
                                ex.getMessage());
                        return Mono.just(errorResult(ex.getMessage()));
                    });
        };
    }

    private URI buildUri(
            @NonNull ServiceInstance instance,
            @NonNull String pathTemplate,
            @NonNull Map<String, Object> pathParams,
            @NonNull Map<String, Object> queryParams) {
        String resolvedPath = resolvePath(pathTemplate, pathParams);
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromUri(instance.getUri()).path(resolvedPath);
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

    private McpSchema.CallToolResult successResult(@NonNull String body) {

        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(body)))
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult errorResult(@NonNull String message) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("Error: " + message)))
                .isError(true)
                .build();
    }
}
