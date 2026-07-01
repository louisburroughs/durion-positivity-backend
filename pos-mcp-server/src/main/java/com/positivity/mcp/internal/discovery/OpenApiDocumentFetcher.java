package com.positivity.mcp.internal.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.config.McpServerProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class OpenApiDocumentFetcher {

    private static final Logger log = LoggerFactory.getLogger(OpenApiDocumentFetcher.class);
    private static final String GATEWAY_SERVICE_ID = "pos-api-gateway";
    private static final URI LOCAL_GATEWAY_FALLBACK = URI.create("http://localhost:8080");
    private static final String SWAGGER_CONFIG_SUFFIX = "/swagger-config";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
                        return aggregateViaSwaggerConfig(specUri, baseUri);
                    }
                    if (openAPI.getPaths() == null || openAPI.getPaths().isEmpty()) {
                        log.info(
                                "Aggregate spec at {} exposes no paths (springdoc gateway); "
                                        + "aggregating per-service specs via swagger-config",
                                specUri);
                        return aggregateViaSwaggerConfig(specUri, baseUri);
                    }
                    return Mono.just(new DiscoveredOpenApi("aggregate", baseUri, openAPI));
                })
                .onErrorResume(ex -> {
                    log.warn("Could not fetch aggregate OpenAPI from {}: {}", specUri, ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Fallback discovery for a springdoc gateway that does not serve a single merged OpenAPI at
     * {@code /v3/api-docs} but instead exposes {@code /v3/api-docs/swagger-config} — an index of
     * per-service doc URLs. Fetches each per-service spec and merges their paths, prefixing every
     * path with the service's gateway routing segment (the first segment of its doc URL, e.g.
     * {@code /accounting/v3/api-docs} → prefix {@code /accounting}) so the merged paths are the
     * full gateway paths (matching how facade tools address the gateway).
     */
    private Mono<DiscoveredOpenApi> aggregateViaSwaggerConfig(URI specUri, URI baseUri) {
        URI swaggerConfigUri = URI.create(specUri.toString() + SWAGGER_CONFIG_SUFFIX);
        String gatewayOwnDocPath = specUri.getPath();
        return webClient
                .get()
                .uri(swaggerConfigUri)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.discoveryTimeout())
                .flatMap(json -> {
                    List<ServiceDoc> docs = parseServiceDocs(json, gatewayOwnDocPath);
                    if (docs.isEmpty()) {
                        log.warn("swagger-config at {} listed no per-service specs", swaggerConfigUri);
                        return Mono.<DiscoveredOpenApi>empty();
                    }
                    return Flux.fromIterable(docs)
                            .flatMap(doc -> fetchAndPrefixService(baseUri, doc))
                            .reduce(new Paths(), (merged, servicePaths) -> {
                                merged.putAll(servicePaths);
                                return merged;
                            })
                            .map(mergedPaths -> {
                                OpenAPI merged = new OpenAPI();
                                merged.setPaths(mergedPaths);
                                log.info(
                                        "Aggregated {} service specs via swagger-config from {} → {} paths",
                                        docs.size(),
                                        swaggerConfigUri,
                                        mergedPaths.size());
                                return new DiscoveredOpenApi("aggregate", baseUri, merged);
                            });
                })
                .onErrorResume(ex -> {
                    log.warn("swagger-config aggregation failed at {}: {}", swaggerConfigUri, ex.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Paths> fetchAndPrefixService(URI baseUri, ServiceDoc doc) {
        URI docUri = baseUri.resolve(doc.url());
        return webClient
                .get()
                .uri(docUri)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.discoveryTimeout())
                .map(raw -> deserialize(doc.routingPrefix(), raw))
                .map(result -> prefixPaths(result.getOpenAPI(), doc.routingPrefix()))
                .doOnNext(paths -> log.info(
                        "Fetched service spec {} → {} paths (prefix {})", docUri, paths.size(), doc.routingPrefix()))
                .onErrorResume(ex -> {
                    log.warn("Could not fetch/merge service spec at {}: {}", docUri, ex.getMessage());
                    return Mono.just(new Paths());
                });
    }

    /**
     * Parses a springdoc {@code swagger-config} JSON body into the per-service docs to aggregate,
     * skipping the gateway's own (empty) spec. Each doc's routing prefix is the first path segment
     * of its doc URL. Pure/testable.
     */
    static List<ServiceDoc> parseServiceDocs(@NonNull String swaggerConfigJson, @NonNull String gatewayOwnDocPath) {
        List<ServiceDoc> docs = new ArrayList<>();
        JsonNode urls;
        try {
            urls = MAPPER.readTree(swaggerConfigJson).get("urls");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.warn("Malformed swagger-config JSON: {}", ex.getMessage());
            return docs;
        }
        if (urls == null || !urls.isArray()) {
            return docs;
        }
        for (JsonNode entry : urls) {
            JsonNode urlNode = entry.get("url");
            if (urlNode == null || urlNode.asText().isBlank()) {
                continue;
            }
            String url = urlNode.asText();
            if (url.equals(gatewayOwnDocPath)) {
                continue; // the gateway's own (empty) spec
            }
            String prefix = firstPathSegment(url);
            if (prefix.isEmpty()) {
                continue;
            }
            docs.add(new ServiceDoc(url, prefix));
        }
        return docs;
    }

    /** Returns a copy of {@code serviceSpec}'s paths with {@code routingPrefix} prepended to each. */
    static @NonNull Paths prefixPaths(@Nullable OpenAPI serviceSpec, @NonNull String routingPrefix) {
        Paths prefixed = new Paths();
        if (serviceSpec == null || serviceSpec.getPaths() == null) {
            return prefixed;
        }
        for (var entry : serviceSpec.getPaths().entrySet()) {
            prefixed.addPathItem(routingPrefix + entry.getKey(), entry.getValue());
        }
        return prefixed;
    }

    private static String firstPathSegment(@NonNull String url) {
        String path = url.startsWith("http") ? URI.create(url).getPath() : url;
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        String segment = slash > 0 ? trimmed.substring(0, slash) : trimmed;
        return segment.isBlank() ? "" : "/" + segment;
    }

    /** A per-service OpenAPI doc discovered via swagger-config: its (gateway-relative) URL and routing prefix. */
    record ServiceDoc(@NonNull String url, @NonNull String routingPrefix) {}

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
