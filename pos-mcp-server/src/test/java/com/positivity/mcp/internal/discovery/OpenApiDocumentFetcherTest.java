package com.positivity.mcp.internal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.positivity.mcp.internal.config.McpServerProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    @DisplayName("fetchAggregateSpec returns empty Mono when aggregateSpecUrl is not configured")
    void fetchAggregateSpec_returnsEmpty_whenAggregateSpecUrlIsBlank() {
        OpenApiDocumentFetcher fetcher = fetcherWith(webClientReturning("irrelevant"), null);

        OpenApiDocumentFetcher.DiscoveredOpenApi result =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(5));

        assertThat(result).isNull();
    }

    // --- helpers ---

    private static OpenApiDocumentFetcher fetcherWith(WebClient webClient, String aggregateSpecUrl) {
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
        return new OpenApiDocumentFetcher(mock(DiscoveryClient.class), webClient, props);
    }

    private static WebClient webClientReturning(String body) {
        ExchangeFunction exchange = request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).body(body).build());
        return WebClient.builder().exchangeFunction(exchange).build();
    }

    private static String loadClasspathResource(String path) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load test resource: " + path, e);
        }
    }
}
