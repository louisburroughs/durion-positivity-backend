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
        McpServerProperties props = new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                includedServices,
                List.of(),
                aggregateSpecUrl,
                List.of());
        return new OpenApiDocumentFetcher(discoveryClient, webClient, props);
    }

    private static OpenApiDocumentFetcher fetcherWith(
            DiscoveryClient discoveryClient, WebClient webClient, String aggregateSpecUrl) {
        McpServerProperties props = new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                List.of(),
                List.of(),
                aggregateSpecUrl,
                List.of());
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
