package com.positivity.mcp.internal.discovery;

import com.positivity.mcp.internal.config.McpServerProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

@Component
public class OpenApiDocumentFetcher {

    private static final Logger log = LoggerFactory.getLogger(OpenApiDocumentFetcher.class);

    private final DiscoveryClient discoveryClient;
    private final WebClient webClient;
    private final McpServerProperties properties;

    OpenApiDocumentFetcher(@NonNull DiscoveryClient discoveryClient,
            @NonNull WebClient discoveryWebClient,
            @NonNull McpServerProperties properties) {
        this.discoveryClient = discoveryClient;
        this.webClient = discoveryWebClient;
        this.properties = properties;
    }

    public @NonNull Mono<DiscoveredOpenApi> fetchForService(@NonNull String serviceId) {
        var instance = pickInstance(serviceId);
        if (instance.isEmpty()) {
            return Mono.empty();
        }
        URI baseUri = instance.get().getUri();
        URI apiDocUri = baseUri.resolve(properties.openApiPath());

        return webClient.get()
                .uri(apiDocUri)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.discoveryTimeout())
                .map(this::deserialize)
                .flatMap(result -> {
                    OpenAPI openAPI = result.getOpenAPI();
                    if (openAPI == null) {
                        log.warn("Failed to parse OpenAPI for service {}: {}", serviceId, result.getMessages());
                        return Mono.empty();
                    }
                    return Mono.just(new DiscoveredOpenApi(serviceId, baseUri, openAPI));
                })
                .onErrorResume(ex -> {
                    log.warn("Could not fetch OpenAPI for service {} at {}: {}", serviceId, apiDocUri, ex.getMessage());
                    return Mono.empty();
                });
    }

    private Optional<ServiceInstance> pickInstance(@NonNull String serviceId) {
        var instances = discoveryClient.getInstances(serviceId);
        if (instances.isEmpty()) {
            return Optional.empty();
        }
        // naive first-instance strategy; load-balanced calls are handled later per
        // request
        return Optional.of(instances.getFirst());
    }

    private SwaggerParseResult deserialize(@NonNull String raw) {
        var parser = new OpenAPIV3Parser();
        var options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        return parser.readContents(raw, null, options);
    }

    public record DiscoveredOpenApi(String serviceId, URI baseUri, OpenAPI openApi) {
    }
}
