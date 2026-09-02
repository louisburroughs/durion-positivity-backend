package com.positivity.mcp.internal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.McpServerProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.util.StreamUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class OpenApiDocumentFetcherTest {

    private static final String AGGREGATE_URL = "http://gateway.test/v3/api-docs";
    private static final URI EXPECTED_BASE_URI = URI.create("http://gateway.test");

    @Test
    @DisplayName("fetchAggregateSpec returns gateway base URI and parsed OpenAPI for valid YAML")
    void fetchAggregateSpec_returnsGatewayBaseUriAndParsedOpenApi_whenYamlIsValid() {
        String yaml = loadClasspathResource("openapi/aggregate/minimal-aggregate.yaml");
        OpenApiDocumentFetcher fetcher = fetcherWith(webClientReturning(yaml), AGGREGATE_URL);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.baseUri()).isEqualTo(EXPECTED_BASE_URI);
        assertThat(result.openApi()).isNotNull();
        assertThat(result.openApi().getInfo().getTitle()).isEqualTo("Positivity API Gateway");
    }

    @Test
    @DisplayName("fetchAggregateSpec returns empty Mono when YAML cannot be parsed as OpenAPI")
    void fetchAggregateSpec_returnsEmpty_whenYamlIsInvalid() {
        String yaml = loadClasspathResource("openapi/aggregate/invalid-aggregate.yaml");
        OpenApiDocumentFetcher fetcher = fetcherWith(webClientReturning(yaml), AGGREGATE_URL);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchAggregateSpec returns empty Mono when HTTP fetch fails")
    void fetchAggregateSpec_returnsEmpty_whenFetchFails() {
        WebClient errorClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new RuntimeException("connection refused")))
                .build();
        OpenApiDocumentFetcher fetcher = fetcherWith(errorClient, AGGREGATE_URL);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchAggregateSpec derives context-path-aware base URI when absolute URL includes a context path")
    void fetchAggregateSpec_derivesContextPathAwareBaseUri_whenSpecUrlIncludesContextPath() {
        String yaml = loadClasspathResource("openapi/aggregate/minimal-aggregate.yaml");
        String specUrlWithContextPath = "http://gateway.example/mcp/v3/api-docs";
        OpenApiDocumentFetcher fetcher = fetcherWith(webClientReturning(yaml), specUrlWithContextPath);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.baseUri()).isEqualTo(URI.create("http://gateway.example/mcp"));
    }

    @Test
    @DisplayName("fetchAggregateSpec returns empty Mono when aggregateSpecUrl is malformed")
    void fetchAggregateSpec_returnsEmpty_whenAggregateSpecUrlIsMalformed() {
        OpenApiDocumentFetcher fetcher = fetcherWith(webClientReturning("irrelevant"), "not a valid url {}");

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchAggregateSpec returns empty Mono when aggregateSpecUrl is not configured")
    void fetchAggregateSpec_returnsEmpty_whenAggregateSpecUrlIsBlank() {
        OpenApiDocumentFetcher fetcher = fetcherWith(webClientReturning("irrelevant"), null);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchAggregateSpec resolves path-only URL against gateway service from discovery")
    void fetchAggregateSpec_resolvesPathAgainstGatewayFromDiscovery_whenConfigIsPathOnly() {
        String yaml = loadClasspathResource("openapi/aggregate/minimal-aggregate.yaml");
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        ServiceInstance gatewayInstance = mock(ServiceInstance.class);
        when(gatewayInstance.getUri()).thenReturn(URI.create("http://gateway.local:8080"));
        when(discoveryClient.getInstances("pos-api-gateway")).thenReturn(List.of(gatewayInstance));

        List<URI> requestedUris = new ArrayList<>();
        WebClient trackingClient = WebClient.builder()
                .exchangeFunction(request -> {
                    requestedUris.add(request.url());
                    return Mono.just(
                            ClientResponse.create(HttpStatus.OK).body(yaml).build());
                })
                .build();

        OpenApiDocumentFetcher fetcher = fetcherWith(discoveryClient, trackingClient, "/v3/api-docs");

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.baseUri()).isEqualTo(URI.create("http://gateway.local:8080"));
        assertThat(requestedUris).hasSize(1);
        assertThat(requestedUris.getFirst().toString()).isEqualTo("http://gateway.local:8080/v3/api-docs");
    }

    @Test
    @DisplayName("fetchAggregateSpec preserves discovered gateway context path for relative aggregate URL")
    void fetchAggregateSpec_preservesGatewayContextPath_whenConfigIsRelativeAndGatewayHasContextPath() {
        String yaml = loadClasspathResource("openapi/aggregate/minimal-aggregate.yaml");
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        ServiceInstance gatewayInstance = mock(ServiceInstance.class);
        when(gatewayInstance.getUri()).thenReturn(URI.create("http://gateway.local:8080/mcp"));
        when(discoveryClient.getInstances("pos-api-gateway")).thenReturn(List.of(gatewayInstance));

        List<URI> requestedUris = new ArrayList<>();
        WebClient trackingClient = WebClient.builder()
                .exchangeFunction(request -> {
                    requestedUris.add(request.url());
                    return Mono.just(
                            ClientResponse.create(HttpStatus.OK).body(yaml).build());
                })
                .build();

        OpenApiDocumentFetcher fetcher = fetcherWith(discoveryClient, trackingClient, "/v3/api-docs");

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.baseUri()).isEqualTo(URI.create("http://gateway.local:8080/mcp"));
        assertThat(requestedUris).hasSize(1);
        assertThat(requestedUris.getFirst().toString()).isEqualTo("http://gateway.local:8080/mcp/v3/api-docs");
    }

    @Test
    @DisplayName("fetchAggregateSpec falls back to http://localhost:8080 when path-only and gateway not discoverable")
    void fetchAggregateSpec_resolvesPathAgainstLocalDefault_whenConfigIsPathOnlyAndGatewayNotDiscoverable() {
        String yaml = loadClasspathResource("openapi/aggregate/minimal-aggregate.yaml");
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        when(discoveryClient.getInstances("pos-api-gateway")).thenReturn(List.of());

        List<URI> requestedUris = new ArrayList<>();
        WebClient trackingClient = WebClient.builder()
                .exchangeFunction(request -> {
                    requestedUris.add(request.url());
                    return Mono.just(
                            ClientResponse.create(HttpStatus.OK).body(yaml).build());
                })
                .build();

        OpenApiDocumentFetcher fetcher = fetcherWith(discoveryClient, trackingClient, "/v3/api-docs");

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.baseUri()).isEqualTo(URI.create("http://localhost:8080"));
        assertThat(requestedUris.getFirst().toString()).isEqualTo("http://localhost:8080/v3/api-docs");
    }

    @Test
    @DisplayName(
            "fetchAggregateSpec marks a failed service fetch in failedPrefixes without losing healthy paths (#1632)")
    void fetchAggregateSpec_recordsFailedPrefixAndKeepsHealthyPaths_whenServiceFetchFails() {
        // 404 is non-transient (isTransient), so the retry backoff never engages and the test stays fast.
        WebClient client = swaggerConfigFallbackClient(
                ClientResponse.create(HttpStatus.NOT_FOUND).build());
        OpenApiDocumentFetcher fetcher = fetcherWith(client, AGGREGATE_URL);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.openApi().getPaths()).containsKey("/alpha/v1/things");
        assertThat(result.failedPrefixes()).containsExactly("/failed");
    }

    @Test
    @DisplayName(
            "fetchAggregateSpec treats a 200-OK unparseable service body as a failure, not an empty service (#1632)")
    void fetchAggregateSpec_recordsFailedPrefix_whenServiceBodyIsUnparseable() {
        // OpenAPIV3Parser returns a result with null getOpenAPI() for garbage input (e.g. a proxy
        // error page); that must land in failedPrefixes, never collapse to success-with-zero-paths.
        WebClient client = swaggerConfigFallbackClient(ClientResponse.create(HttpStatus.OK)
                .body("<html><body>502 Bad Gateway</body></html>")
                .build());
        OpenApiDocumentFetcher fetcher = fetcherWith(client, AGGREGATE_URL);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.openApi().getPaths()).containsKey("/alpha/v1/things");
        assertThat(result.failedPrefixes()).containsExactly("/failed");
    }

    @Test
    @DisplayName("fetchAggregateSpec reports no failedPrefixes when every service spec fetch succeeds (#1632)")
    void fetchAggregateSpec_reportsNoFailedPrefixes_whenAllServiceFetchesSucceed() {
        WebClient client = swaggerConfigFallbackClient(ClientResponse.create(HttpStatus.OK)
                .body(serviceSpecJson("Failed", "/v1/other"))
                .build());
        OpenApiDocumentFetcher fetcher = fetcherWith(client, AGGREGATE_URL);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.openApi().getPaths()).containsKeys("/alpha/v1/things", "/failed/v1/other");
        assertThat(result.failedPrefixes()).isEmpty();
    }

    @Test
    @DisplayName("fetchAggregateSpec treats a parseable spec whose title names another service as a failed fetch "
            + "(#1632 spec-identity guard)")
    void fetchAggregateSpec_recordsFailedPrefix_whenSpecTitleBelongsToAnotherService() {
        // The alpha 2026-09-01 incident: a stale Eureka registration routed /invoice to pos-price,
        // so the fetch succeeded (200 OK, valid spec) but carried the WRONG service's operations.
        // The identity guard must turn that into a fetch failure, not a silent re-labeling.
        WebClient client = swaggerConfigFallbackClient(ClientResponse.create(HttpStatus.OK)
                .body(serviceSpecJson("Positivity Price API", "/v1/promotions"))
                .build());
        OpenApiDocumentFetcher fetcher = fetcherWith(client, AGGREGATE_URL);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.openApi().getPaths()).containsKey("/alpha/v1/things");
        assertThat(result.openApi().getPaths()).doesNotContainKey("/failed/v1/promotions");
        assertThat(result.failedPrefixes()).containsExactly("/failed");
    }

    @Test
    @DisplayName("fetchAggregateSpec accepts a title that only matches via a configured spec-identity alias (#1632)")
    void fetchAggregateSpec_acceptsSpec_whenTitleMatchesConfiguredAlias() {
        // pos-catalog's real title is "Positivity Product API" — no "catalog" token. The configured
        // alias (catalog → product) must keep the guard from failing a healthy fetch.
        WebClient client = swaggerConfigFallbackClient(ClientResponse.create(HttpStatus.OK)
                .body(serviceSpecJson("Positivity Product API", "/v1/items"))
                .build());
        OpenApiDocumentFetcher fetcher = fetcherWith(
                mock(DiscoveryClient.class), client, AGGREGATE_URL, List.of(), Map.of("failed", List.of("product")));

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.openApi().getPaths()).containsKeys("/alpha/v1/things", "/failed/v1/items");
        assertThat(result.failedPrefixes()).isEmpty();
    }

    @Test
    @DisplayName("fetchAggregateSpec honors an alias keyed by the hyphenated routing token, not just the "
            + "normalized form (PR #1643 review)")
    void fetchAggregateSpec_acceptsSpec_whenAliasIsKeyedByHyphenatedRoutingToken() {
        // The natural way to write the property is the routing token as it appears in the URL
        // ("vehicle-fitment"); the guard normalizes tokens internally ("vehiclefitment") and must
        // look aliases up under BOTH spellings, or a hyphenated domain's alias silently never
        // applies and the guard false-fails a healthy service.
        ExchangeFunction exchange = request -> Mono.just(
                switch (request.url().getPath()) {
                    case "/v3/api-docs" ->
                        ClientResponse.create(HttpStatus.OK).body("""
                            {"openapi":"3.0.1","info":{"title":"Positivity API Gateway","version":"v1"},"paths":{}}
                            """).build();
                    case "/v3/api-docs/swagger-config" ->
                        ClientResponse.create(HttpStatus.OK).body("""
                            {"urls":[{"url":"/vehicle-fitment/v3/api-docs","name":"vehicle-fitment"}]}
                            """).build();
                    case "/vehicle-fitment/v3/api-docs" ->
                        ClientResponse.create(HttpStatus.OK)
                                .body(serviceSpecJson("Tires API", "/v1/fitments"))
                                .build();
                    default -> throw new IllegalStateException("Unexpected request: " + request.url());
                });
        WebClient client = WebClient.builder().exchangeFunction(exchange).build();
        OpenApiDocumentFetcher fetcher = fetcherWith(
                mock(DiscoveryClient.class),
                client,
                AGGREGATE_URL,
                List.of(),
                Map.of("vehicle-fitment", List.of("tires")));

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.openApi().getPaths()).containsKey("/vehicle-fitment/v1/fitments");
        assertThat(result.failedPrefixes()).isEmpty();
    }

    @Test
    @DisplayName("fetchAggregateSpec passes a spec with the unverifiable springdoc default title (#1632)")
    void fetchAggregateSpec_acceptsSpec_whenTitleIsUnverifiableDefault() {
        // Services without an OpenApiConfig serve springdoc's default "OpenAPI definition" title.
        // The guard is best-effort: what cannot be verified must never be failed.
        WebClient client = swaggerConfigFallbackClient(ClientResponse.create(HttpStatus.OK)
                .body(serviceSpecJson("OpenAPI definition", "/v1/other"))
                .build());
        OpenApiDocumentFetcher fetcher = fetcherWith(client, AGGREGATE_URL);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.openApi().getPaths()).containsKeys("/alpha/v1/things", "/failed/v1/other");
        assertThat(result.failedPrefixes()).isEmpty();
    }

    @Test
    @DisplayName("fetchForService skips a spec whose title names another service (#1632 spec-identity guard)")
    void fetchForService_returnsEmpty_whenSpecTitleBelongsToAnotherService() {
        // The per-service Eureka path (and the targeted failed-prefix fallback built on it) is just
        // as exposed to stale instance URIs as the gateway path — it must not hand back the wrong
        // domain's spec under this service id.
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        ServiceInstance instance = mock(ServiceInstance.class);
        when(instance.getUri()).thenReturn(URI.create("http://invoice.local:8081"));
        when(discoveryClient.getInstances("invoice")).thenReturn(List.of(instance));
        WebClient client = webClientReturning(serviceSpecJson("Positivity Price API", "/v1/promotions"));
        OpenApiDocumentFetcher fetcher = fetcherWith(discoveryClient, client, AGGREGATE_URL);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchForService("invoice").block(Duration.ofSeconds(5));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchForService accepts a spec whose title contains the service id token")
    void fetchForService_returnsSpec_whenTitleMatchesServiceId() {
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        ServiceInstance instance = mock(ServiceInstance.class);
        when(instance.getUri()).thenReturn(URI.create("http://invoice.local:8081"));
        when(discoveryClient.getInstances("invoice")).thenReturn(List.of(instance));
        WebClient client = webClientReturning(serviceSpecJson("POS Invoice API", "/v1/invoices"));
        OpenApiDocumentFetcher fetcher = fetcherWith(discoveryClient, client, AGGREGATE_URL);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchForService("invoice").block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.openApi().getPaths()).containsKey("/v1/invoices");
    }

    @Test
    @DisplayName("identityToken strips the leading slash, the pos- module prefix, and non-alphanumerics")
    void identityToken_normalizesPrefixesAndServiceIds() {
        assertThat(OpenApiDocumentFetcher.identityToken("/vehicle-fitment")).isEqualTo("vehiclefitment");
        assertThat(OpenApiDocumentFetcher.identityToken("/security-service")).isEqualTo("securityservice");
        assertThat(OpenApiDocumentFetcher.identityToken("pos-order")).isEqualTo("order");
        assertThat(OpenApiDocumentFetcher.identityToken("INVOICE")).isEqualTo("invoice");
    }

    @Test
    @DisplayName("specIdentityMatches accepts real platform titles for their own routing tokens and rejects "
            + "cross-service titles")
    void specIdentityMatches_matchesRealPlatformTitles() {
        // Every configured pos-* title verified against its own routing token (the guard must not
        // fail any healthy domain on alpha).
        assertThat(OpenApiDocumentFetcher.specIdentityMatches("POS Invoice API", "invoice", List.of()))
                .isTrue();
        assertThat(OpenApiDocumentFetcher.specIdentityMatches("POS Workorder Service API", "workorder", List.of()))
                .isTrue();
        assertThat(OpenApiDocumentFetcher.specIdentityMatches("POS Accounting Service API", "accounting", List.of()))
                .isTrue();
        assertThat(OpenApiDocumentFetcher.specIdentityMatches("Vehicle Fitment API", "vehiclefitment", List.of()))
                .isTrue();
        assertThat(OpenApiDocumentFetcher.specIdentityMatches("POS Security Service API", "securityservice", List.of()))
                .isTrue();
        // The alpha incident: price's title under the invoice token must be a mismatch.
        assertThat(OpenApiDocumentFetcher.specIdentityMatches("Positivity Price API", "invoice", List.of()))
                .isFalse();
        // Aliased outliers.
        assertThat(OpenApiDocumentFetcher.specIdentityMatches("Positivity Product API", "catalog", List.of("product")))
                .isTrue();
        assertThat(OpenApiDocumentFetcher.specIdentityMatches(
                        "POS Human Resources Service API", "people", List.of("human resources")))
                .isTrue();
        // Unverifiable titles pass.
        assertThat(OpenApiDocumentFetcher.specIdentityMatches(null, "invoice", List.of()))
                .isTrue();
        assertThat(OpenApiDocumentFetcher.specIdentityMatches("  ", "invoice", List.of()))
                .isTrue();
        assertThat(OpenApiDocumentFetcher.specIdentityMatches("OpenAPI definition", "invoice", List.of()))
                .isTrue();
    }

    // --- helpers ---

    @Test
    @DisplayName("fallbackServiceIds returns the configured included-services allowlist when set (#645)")
    void fallbackServiceIds_returnsConfiguredAllowlist_whenSet() {
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        OpenApiDocumentFetcher fetcher = fetcherWith(
                discoveryClient, webClientReturning("irrelevant"), AGGREGATE_URL, List.of("pos-order", "pos-catalog"));

        assertThat(fetcher.fallbackServiceIds()).containsExactly("pos-order", "pos-catalog");
        // The registry is not consulted when an explicit allowlist is configured.
        verifyNoInteractions(discoveryClient);
    }

    @Test
    @DisplayName("fallbackServiceIds falls back to registered services minus the gateway when no allowlist (#645)")
    void fallbackServiceIds_returnsRegisteredServicesMinusGateway_whenNoAllowlist() {
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        when(discoveryClient.getServices()).thenReturn(List.of("pos-order", "pos-api-gateway", "pos-catalog"));
        OpenApiDocumentFetcher fetcher =
                fetcherWith(discoveryClient, webClientReturning("irrelevant"), AGGREGATE_URL, List.of());

        assertThat(fetcher.fallbackServiceIds()).containsExactly("pos-order", "pos-catalog");
    }

    private static OpenApiDocumentFetcher fetcherWith(WebClient webClient, String aggregateSpecUrl) {
        return fetcherWith(mock(DiscoveryClient.class), webClient, aggregateSpecUrl);
    }

    private static OpenApiDocumentFetcher fetcherWith(
            DiscoveryClient discoveryClient,
            WebClient webClient,
            String aggregateSpecUrl,
            List<String> includedServices) {
        return fetcherWith(discoveryClient, webClient, aggregateSpecUrl, includedServices, Map.of());
    }

    private static OpenApiDocumentFetcher fetcherWith(
            DiscoveryClient discoveryClient, WebClient webClient, String aggregateSpecUrl) {
        return fetcherWith(discoveryClient, webClient, aggregateSpecUrl, List.of(), Map.of());
    }

    private static OpenApiDocumentFetcher fetcherWith(
            DiscoveryClient discoveryClient,
            WebClient webClient,
            String aggregateSpecUrl,
            List<String> includedServices,
            Map<String, List<String>> specIdentityAliases) {
        McpServerProperties props = new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                includedServices,
                List.of(),
                aggregateSpecUrl,
                List.of(),
                specIdentityAliases);
        return new OpenApiDocumentFetcher(discoveryClient, webClient, props);
    }

    /**
     * A WebClient driving {@code fetchAggregateSpec()} into the swagger-config fallback: the
     * aggregate URL serves a valid zero-paths spec, swagger-config lists {@code /alpha} (a healthy
     * one-path service) and {@code /failed} (whose response is supplied by the test).
     */
    private static WebClient swaggerConfigFallbackClient(ClientResponse failedServiceResponse) {
        ExchangeFunction exchange = request -> Mono.just(
                switch (request.url().getPath()) {
                    case "/v3/api-docs" ->
                        ClientResponse.create(HttpStatus.OK).body("""
                            {"openapi":"3.0.1","info":{"title":"Positivity API Gateway","version":"v1"},"paths":{}}
                            """).build();
                    case "/v3/api-docs/swagger-config" ->
                        ClientResponse.create(HttpStatus.OK).body("""
                            {"urls":[{"url":"/alpha/v3/api-docs","name":"alpha"},{"url":"/failed/v3/api-docs","name":"failed"}]}
                            """).build();
                    case "/alpha/v3/api-docs" ->
                        ClientResponse.create(HttpStatus.OK)
                                .body(serviceSpecJson("Alpha", "/v1/things"))
                                .build();
                    case "/failed/v3/api-docs" -> failedServiceResponse;
                    default -> throw new IllegalStateException("Unexpected request: " + request.url());
                });
        return WebClient.builder().exchangeFunction(exchange).build();
    }

    private static String serviceSpecJson(String title, String path) {
        return """
                {"openapi":"3.0.1","info":{"title":"%s","version":"v1"},"paths":{"%s":{"get":{"operationId":"list%s","responses":{"200":{"description":"ok"}}}}}}
                """.formatted(title, path, title);
    }

    private static WebClient webClientReturning(String body) {
        ExchangeFunction exchange = request ->
                Mono.just(ClientResponse.create(HttpStatus.OK).body(body).build());
        return WebClient.builder().exchangeFunction(exchange).build();
    }

    private static String loadClasspathResource(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load test resource: " + path, e);
        }
    }
}
