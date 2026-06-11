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
        URI specUri;
        try {
            specUri = URI.create(specUrl);
        } catch (IllegalArgumentException ex) {
            log.warn(
                    "Malformed mcp.server.aggregate-spec-url '{}', skipping aggregate discovery: {}",
                    specUrl,
                    ex.getMessage());
            return Mono.empty();
        }
        if (!specUri.isAbsolute()) {
            URI gatewayBase = resolveGatewayBase();
            return fetchAggregateSpecFrom(resolveRelativeSpecUri(gatewayBase, specUri), gatewayBase);
        }
        URI baseUri = deriveBaseUri(specUri);
        return fetchAggregateSpecFrom(specUri, baseUri);
    }

    /**
     * Derives the routing base URI from an absolute spec URL by stripping the configured
     * {@code openApiPath} suffix (e.g. {@code /v3/api-docs}) from the spec URL path. This
     * preserves any context-path prefix the gateway may have.
     *
     * <p>Examples (with {@code openApiPath=/v3/api-docs}):
     * <ul>
     *   <li>{@code http://gateway.test/v3/api-docs} → {@code http://gateway.test}
     *   <li>{@code https://gateway.example/mcp/v3/api-docs} → {@code https://gateway.example/mcp}
     * </ul>
     *
     * <p>If the spec URL does not end with the configured {@code openApiPath}, the last path
     * segment is stripped as a fallback.
     */
    private URI deriveBaseUri(URI specUri) {
        String specPath = specUri.getPath();
        String openApiPath = properties.openApiPath();
        String basePath;
        if (specPath != null && specPath.endsWith(openApiPath)) {
            basePath = specPath.substring(0, specPath.length() - openApiPath.length());
        } else {
            int lastSlash = specPath != null ? specPath.lastIndexOf('/') : -1;
            basePath = lastSlash > 0 && specPath != null ? specPath.substring(0, lastSlash) : "";
        }
        if (basePath.isEmpty() || basePath.equals("/")) {
            return URI.create(specUri.getScheme() + "://" + specUri.getAuthority());
        }
        return URI.create(specUri.getScheme() + "://" + specUri.getAuthority() + basePath);
    }

    private URI resolveGatewayBase() {
        var instances = discoveryClient.getInstances(GATEWAY_SERVICE_ID);
        if (!instances.isEmpty()) {
            return instances.getFirst().getUri();
        }
        return LOCAL_GATEWAY_FALLBACK;
    }

    private URI resolveRelativeSpecUri(URI gatewayBase, URI relativeSpecUri) {
        String gatewayPath = normalizeBasePath(gatewayBase.getPath());
        String specPath = normalizeSpecPath(relativeSpecUri.getPath());
        String resolvedPath = gatewayPath + specPath;
        if (resolvedPath.isEmpty()) {
            resolvedPath = "/";
        }
        try {
            return new URI(
                    gatewayBase.getScheme(),
                    gatewayBase.getUserInfo(),
                    gatewayBase.getHost(),
                    gatewayBase.getPort(),
                    resolvedPath,
                    relativeSpecUri.getQuery(),
                    relativeSpecUri.getFragment());
        } catch (java.net.URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid aggregate spec URI path: " + relativeSpecUri, ex);
        }
    }

    private String normalizeBasePath(String gatewayPath) {
        if (gatewayPath == null || gatewayPath.isBlank() || "/".equals(gatewayPath)) {
            return "";
        }
        return gatewayPath.endsWith("/") ? gatewayPath.substring(0, gatewayPath.length() - 1) : gatewayPath;
    }

    private String normalizeSpecPath(String specPath) {
        if (specPath == null || specPath.isBlank()) {
            return "";
        }
        return specPath.startsWith("/") ? specPath : "/" + specPath;
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
