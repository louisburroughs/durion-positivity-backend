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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class OpenApiDocumentFetcher {
    private static final String AGGREGATE = "aggregate";

    private static final Logger log = LoggerFactory.getLogger(OpenApiDocumentFetcher.class);
    private static final String GATEWAY_SERVICE_ID = "pos-api-gateway";
    private static final URI LOCAL_GATEWAY_FALLBACK = URI.create("http://localhost:8080");
    private static final String SWAGGER_CONFIG_SUFFIX = "/swagger-config";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Discovery runs at startup; a cold service mesh returns 503 / times out on the first hit. Retry
    // transient failures with backoff so a warming service is picked up without a full app restart.
    private static final int DISCOVERY_RETRY_ATTEMPTS = 3;
    private static final Duration DISCOVERY_RETRY_MIN_BACKOFF = Duration.ofSeconds(2);
    private static final Duration DISCOVERY_RETRY_MAX_BACKOFF = Duration.ofSeconds(10);

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
            // relativeSpecUri is derived from mcp.server.aggregate-spec-url (server config) and
            // this only runs from the startup/scheduled discovery path, never a controller thread
            // (#1694) -- left as a bare IllegalArgumentException.
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
                .map(raw -> deserialize(AGGREGATE, raw))
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
                    return Mono.just(new DiscoveredOpenApi(AGGREGATE, baseUri, openAPI));
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
                            // Concurrency 4: concurrent up-to-16MB spec bodies + resolveFully parses are
                            // the dominant memory cost of discovery; bounding the fan-out keeps startup
                            // memory spikes bounded.
                            .flatMap(doc -> fetchAndPrefixService(baseUri, doc), 4)
                            .collectList()
                            .map(results -> {
                                Paths mergedPaths = new Paths();
                                List<String> failedPrefixes = new ArrayList<>();
                                for (ServiceFetchResult result : results) {
                                    if (result.failed()) {
                                        failedPrefixes.add(result.routingPrefix());
                                    } else {
                                        mergedPaths.putAll(result.paths());
                                    }
                                }
                                OpenAPI merged = new OpenAPI();
                                merged.setPaths(mergedPaths);
                                if (failedPrefixes.isEmpty()) {
                                    log.info(
                                            "Aggregated {} service specs via swagger-config from {} → {} paths",
                                            docs.size(),
                                            swaggerConfigUri,
                                            mergedPaths.size());
                                } else {
                                    // ERROR, not WARN: a partial aggregate silently narrows the
                                    // assistant's tool surface for whole domains (#1632).
                                    log.error(
                                            "Aggregated {} of {} service specs via swagger-config from {} → {} paths; "
                                                    + "FAILED prefixes {} — their previously-registered ops will be "
                                                    + "kept, not pruned, this cycle",
                                            docs.size() - failedPrefixes.size(),
                                            docs.size(),
                                            swaggerConfigUri,
                                            mergedPaths.size(),
                                            failedPrefixes);
                                }
                                return new DiscoveredOpenApi(AGGREGATE, baseUri, merged, List.copyOf(failedPrefixes));
                            });
                })
                .onErrorResume(ex -> {
                    log.warn("swagger-config aggregation failed at {}: {}", swaggerConfigUri, ex.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<ServiceFetchResult> fetchAndPrefixService(URI baseUri, ServiceDoc doc) {
        URI docUri = baseUri.resolve(doc.url());
        return webClient
                .get()
                .uri(docUri)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.discoveryTimeout())
                .retryWhen(Retry.backoff(DISCOVERY_RETRY_ATTEMPTS, DISCOVERY_RETRY_MIN_BACKOFF)
                        .maxBackoff(DISCOVERY_RETRY_MAX_BACKOFF)
                        .filter(OpenApiDocumentFetcher::isTransient))
                .map(raw -> deserialize(doc.routingPrefix(), raw))
                .map(result -> {
                    OpenAPI openAPI = result.getOpenAPI();
                    if (openAPI == null) {
                        // OpenAPIV3Parser does not throw on garbage input — it returns a result
                        // whose OpenAPI is null. A 200-OK non-spec body (proxy error page,
                        // truncated spec) must be a failure, not a clean empty service, or the
                        // #1121 prune deletes this domain's registered ops (#1632).
                        log.warn(
                                "Unparseable service spec at {} (prefix {}): {}",
                                docUri,
                                doc.routingPrefix(),
                                result.getMessages());
                        return ServiceFetchResult.failure(doc.routingPrefix());
                    }
                    if (identityMismatch(openAPI, doc.routingPrefix())) {
                        // #1632 defect 3: a stale Eureka registration can route this prefix to a
                        // DIFFERENT service's spec — 200 OK and parseable, so neither the transport
                        // guard nor the parse guard fires, and the wrong domain's ops would be
                        // registered under this prefix while the real ones are pruned as stale.
                        // Treat a wrong-identity spec exactly like a failed fetch.
                        log.warn(
                                "Service spec at {} (prefix {}) has title '{}' which does not match the "
                                        + "routing prefix — likely a stale gateway/Eureka route serving "
                                        + "another service's spec; treating as a failed fetch (#1632)",
                                docUri,
                                doc.routingPrefix(),
                                specTitle(openAPI));
                        return ServiceFetchResult.failure(doc.routingPrefix());
                    }
                    Paths paths = prefixPaths(openAPI, doc.routingPrefix());
                    log.info(
                            "Fetched service spec {} → {} paths (prefix {})",
                            docUri,
                            paths.size(),
                            doc.routingPrefix());
                    return ServiceFetchResult.success(doc.routingPrefix(), paths);
                })
                // #1632: a failed fetch must stay distinguishable from an empty service. Collapsing
                // to an empty Paths made a transient failure look like a removed service, and the
                // #1121 prune then deleted that domain's previously-registered ops. Log the CAUSE
                // too — Retry exhaustion reports only "Retries exhausted: 3/3" in getMessage().
                .onErrorResume(ex -> {
                    Throwable cause = ex.getCause();
                    log.warn(
                            "Could not fetch/merge service spec at {} (prefix {}): {}{}",
                            docUri,
                            doc.routingPrefix(),
                            ex.getMessage(),
                            cause != null ? " — cause: " + cause : "");
                    return Mono.just(ServiceFetchResult.failure(doc.routingPrefix()));
                });
    }

    /** Outcome of one per-service spec fetch: its prefixed paths on success, or a failure marker. */
    record ServiceFetchResult(
            @NonNull String routingPrefix, @NonNull Paths paths, boolean failed) {
        static ServiceFetchResult success(@NonNull String prefix, @NonNull Paths paths) {
            return new ServiceFetchResult(prefix, paths, false);
        }

        static ServiceFetchResult failure(@NonNull String prefix) {
            return new ServiceFetchResult(prefix, new Paths(), true);
        }
    }

    /**
     * Transient failures worth retrying during startup discovery: request timeouts, connection
     * errors, and 5xx (a service still warming up returns 503). A 4xx (e.g. 404) is not retried.
     */
    static boolean isTransient(@NonNull Throwable ex) {
        if (ex instanceof TimeoutException || ex instanceof WebClientRequestException) {
            return true;
        }
        return ex instanceof WebClientResponseException response
                && response.getStatusCode().is5xxServerError();
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

    /**
     * Spec-identity guard (#1632 defect 3). True when the fetched spec's {@code info.title}
     * verifiably belongs to a different service than {@code prefixOrServiceId} routes to. A spec
     * whose title is missing, blank, or the springdoc default ("OpenAPI definition") is
     * unverifiable and passes — the guard is best-effort and must never fail a service that simply
     * hasn't configured a title. Titles that don't contain the routing token get a second chance
     * via {@code mcp.server.spec-identity-aliases} (e.g. catalog → "product").
     */
    private boolean identityMismatch(@NonNull OpenAPI openAPI, @NonNull String prefixOrServiceId) {
        String token = identityToken(prefixOrServiceId);
        if (token.isEmpty()) {
            return false;
        }
        return !specIdentityMatches(specTitle(openAPI), token, aliasesFor(token, prefixOrServiceId));
    }

    /**
     * Aliases may be keyed by the natural spelling of the routing token ({@code vehicle-fitment},
     * what the property name implies) or by the normalized identity token ({@code vehiclefitment});
     * accept both, otherwise an alias for a hyphenated domain silently never applies and the guard
     * false-fails a healthy service (PR #1643 review).
     */
    private @NonNull List<String> aliasesFor(@NonNull String normalizedToken, @NonNull String prefixOrServiceId) {
        String rawKey = (prefixOrServiceId.startsWith("/") ? prefixOrServiceId.substring(1) : prefixOrServiceId)
                .toLowerCase(Locale.ROOT);
        if (rawKey.startsWith("pos-")) {
            // A pos-prefixed Eureka id ("pos-vehicle-fitment") must find the same natural-spelling
            // key ("vehicle-fitment") as the routing prefix does, or the Eureka fallback path
            // re-opens the silent-miss trap the dual-key lookup fixed (#1643 review).
            rawKey = rawKey.substring(4);
        }
        List<String> normalized = properties.identityAliasesFor(normalizedToken);
        if (rawKey.equals(normalizedToken)) {
            return normalized;
        }
        List<String> raw = properties.identityAliasesFor(rawKey);
        if (raw.isEmpty()) {
            return normalized;
        }
        if (normalized.isEmpty()) {
            return raw;
        }
        List<String> merged = new ArrayList<>(normalized);
        merged.addAll(raw);
        return merged;
    }

    private static @Nullable String specTitle(@NonNull OpenAPI openAPI) {
        return openAPI.getInfo() != null ? openAPI.getInfo().getTitle() : null;
    }

    /**
     * Normalizes a routing prefix or Eureka service id to the token the identity check matches on:
     * leading slash and conventional {@code pos-} module prefix stripped, lowercased, non-alphanumerics
     * removed ({@code "/vehicle-fitment"} → {@code "vehiclefitment"}, {@code "pos-order"} → {@code
     * "order"}).
     */
    static @NonNull String identityToken(@NonNull String prefixOrServiceId) {
        String stripped = prefixOrServiceId.startsWith("/") ? prefixOrServiceId.substring(1) : prefixOrServiceId;
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (lower.startsWith("pos-")) {
            lower = lower.substring(4);
        }
        return lower.replaceAll("[^a-z0-9]", "");
    }

    /**
     * True when {@code title} plausibly identifies the service {@code expectedToken} routes to: the
     * normalized title contains the token or one of its configured aliases. Null/blank and the
     * springdoc default title are unverifiable → true (never fail what can't be checked).
     *
     * <p>Known blind spots (#1643 review): containment cannot catch a stale route between
     * token-nested domains — pos-people-contact's title served at {@code /people} passes (contains
     * "people"), pos-workorder's at {@code /order} passes (contains "order"), pos-vehicle-inventory's
     * at {@code /inventory} passes. The failure direction there is no worse than pre-guard (the
     * pre-#1632 behavior), and untitled specs are inherently unverifiable; do not treat a passing
     * guard as proof the route is correct when debugging those pairs.
     */
    static boolean specIdentityMatches(
            @Nullable String title, @NonNull String expectedToken, @NonNull List<String> aliases) {
        if (title == null || title.isBlank()) {
            return true;
        }
        String normalizedTitle = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalizedTitle.isEmpty() || normalizedTitle.equals("openapidefinition")) {
            return true;
        }
        if (normalizedTitle.contains(expectedToken)) {
            return true;
        }
        return aliases.stream()
                .map(alias -> alias.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                .anyMatch(alias -> !alias.isEmpty() && normalizedTitle.contains(alias));
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

    /**
     * Candidate service ids for the #645 per-service Eureka discovery fallback: the configured
     * {@code mcp.server.included-services} allowlist when non-empty, otherwise every Eureka-registered
     * service except the gateway itself (whose aggregate spec is what the fallback is compensating
     * for). Returned in registry order; callers treat each as fail-soft.
     */
    public @NonNull List<String> fallbackServiceIds() {
        if (!properties.includedServices().isEmpty()) {
            return List.copyOf(properties.includedServices());
        }
        return discoveryClient.getServices().stream()
                .filter(id -> id != null && !GATEWAY_SERVICE_ID.equalsIgnoreCase(id))
                .toList();
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
                    if (identityMismatch(openAPI, serviceId)) {
                        // #1632 defect 3: the Eureka instance URI can be stale after a rolling
                        // deploy (Docker reassigned the IP to another service), so this path is
                        // just as exposed as the gateway one — and it backs the targeted
                        // failed-prefix fallback, which must not re-register the wrong domain.
                        log.warn(
                                "OpenAPI for service {} at {} has title '{}' which does not match the "
                                        + "service id — likely a stale Eureka instance serving another "
                                        + "service's spec; skipping (#1632)",
                                serviceId,
                                apiDocUri,
                                specTitle(openAPI));
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

    /**
     * @param failedPrefixes routing prefixes whose per-service spec fetch FAILED this cycle (empty
     *     when the aggregate came from a single merged document). #1632: a failed fetch is not an
     *     empty service — consumers must not treat the aggregate as complete when this is non-empty,
     *     and in particular must not prune previously-registered ops for these prefixes.
     */
    public record DiscoveredOpenApi(String serviceId, URI baseUri, OpenAPI openApi, List<String> failedPrefixes) {
        public DiscoveredOpenApi(String serviceId, URI baseUri, OpenAPI openApi) {
            this(serviceId, baseUri, openApi, List.of());
        }
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
