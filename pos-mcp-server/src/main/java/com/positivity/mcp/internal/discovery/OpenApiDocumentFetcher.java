package com.positivity.mcp.internal.discovery;

import com.positivity.mcp.internal.config.McpServerProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.net.URI;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class OpenApiDocumentFetcher {

    private static final Logger log = LoggerFactory.getLogger(OpenApiDocumentFetcher.class);
    private static final String GATEWAY_SERVICE_ID = "pos-api-gateway";
    private static final URI LOCAL_GATEWAY_FALLBACK = URI.create("http://localhost:8080");

    private final DiscoveryClient discoveryClient;
    private final WebClient webClient;
    private final McpServerProperties properties;

    OpenApiDocumentFetcher(
            @NonNull DiscoveryClient discoveryClient,
            @NonNull WebClient discoveryWebClient,
            @NonNull McpServerProperties properties) {
        this.discoveryClient = discoveryClient;
        this.webClient = discoveryWebClient;
        this.properties = properties;
    }

    public @NonNull Mono<DiscoveredOpenApi> fetchAggregateSpec() {
        String specUrl = properties.aggregateSpecUrl();
        if (specUrl == null || specUrl.isBlank()) {
            return Mono.empty();
        }
        URI specUri = URI.create(specUrl);
        if (!specUri.isAbsolute()) {
            URI gatewayBase = resolveGatewayBase();
            return fetchAggregateSpecFrom(gatewayBase.resolve(specUri), gatewayBase);
        }
        URI baseUri = URI.create(specUri.getScheme() + "://" + specUri.getAuthority());
        return fetchAggregateSpecFrom(specUri, baseUri);
    }

    private URI resolveGatewayBase() {
        var instances = discoveryClient.getInstances(GATEWAY_SERVICE_ID);
        if (!instances.isEmpty()) {
            return instances.getFirst().getUri();
        }
        return LOCAL_GATEWAY_FALLBACK;
    }

    private Mono<DiscoveredOpenApi> fetchAggregateSpecFrom(URI specUri, URI baseUri) {
        long fetchStartNanos = System.nanoTime();
        return webClient
                .get()
                .uri(specUri)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.discoveryTimeout())
                .doOnNext(raw -> log.info(
                        "Fetched aggregate OpenAPI from {} in {} ms ({} bytes)",
                        specUri,
                        elapsedMs(fetchStartNanos),
                        raw.length()))
                .map(raw -> deserialize("aggregate", raw))
                .flatMap(result -> {
                    OpenAPI openAPI = result.getOpenAPI();
                    if (openAPI == null) {
                        log.warn("Failed to parse aggregate OpenAPI from {}: {}", specUri, result.getMessages());
                        return Mono.<DiscoveredOpenApi>empty();
                    }
                    return Mono.just(new DiscoveredOpenApi("aggregate", baseUri, openAPI));
                })
                .onErrorResume(ex -> {
                    log.warn("Could not fetch aggregate OpenAPI from {}: {}", specUri, ex.getMessage());
                    return Mono.empty();
                });
    }

    public @NonNull Mono<DiscoveredOpenApi> fetchForService(@NonNull String serviceId) {
        var instance = pickInstance(serviceId);
        if (instance.isEmpty()) {
            return Mono.empty();
        }
        URI baseUri = instance.get().getUri();
        URI apiDocUri = baseUri.resolve(properties.openApiPath());
        long fetchStartNanos = System.nanoTime();

        return webClient
                .get()
                .uri(apiDocUri)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.discoveryTimeout())
                .doOnNext(raw -> log.info(
                        "Fetched OpenAPI for service {} from {} in {} ms ({} bytes)",
                        serviceId,
                        apiDocUri,
                        elapsedMs(fetchStartNanos),
                        raw.length()))
                .map(raw -> deserialize(serviceId, raw))
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

    private SwaggerParseResult deserialize(@NonNull String serviceId, @NonNull String raw) {
        long parseStartNanos = System.nanoTime();
        var parser = new OpenAPIV3Parser();
        var options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result = parser.readContents(raw, null, options);
        log.info("Parsed OpenAPI for service {} in {} ms", serviceId, elapsedMs(parseStartNanos));
        return result;
    }

    public record DiscoveredOpenApi(String serviceId, URI baseUri, OpenAPI openApi) {}

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
