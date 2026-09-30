package com.positivity.mcp.internal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.scopegraph.OpenApiSchemaIndex;
import com.positivity.mcp.internal.scopegraph.OpenApiSchemaIndexHolder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * ADR-0069 (spec 2.2): the schema index is captured per service spec while discovery still holds
 * the whole spec, keyed by the persisted tool name, and only when the scope graph is on. Discovery's
 * own result is the same either way.
 */
class OpenApiDocumentFetcherSchemaCaptureTest {

    private static final String AGGREGATE_URL = "http://gateway.test/v3/api-docs";
    private static final ScopeGraphProperties SHADOW =
            new ScopeGraphProperties(ScopeGraphProperties.Mode.SHADOW, List.of(), 0, 0, 0);
    private static final String SERVICE_SPEC = read("scope-graph/service-spec.yaml");

    @Test
    @DisplayName("swagger-config aggregation indexes each service spec under its routing prefix before the merge")
    void capturesPerServiceSpecBeforeMerge() {
        OpenApiSchemaIndexHolder holder = new OpenApiSchemaIndexHolder();
        OpenApiDocumentFetcher fetcher = fetcher(swaggerConfigClient(), SHADOW, holder);

        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                fetcher.fetchAggregateSpec().block(Duration.ofSeconds(10));

        // The merged aggregate carries paths only: the schemas would be gone without the capture.
        assertThat(discovered).isNotNull();
        assertThat(discovered.openApi().getComponents()).isNull();
        assertThat(discovered.openApi().getPaths()).containsKey("/workorder/v1/workorders/{id}");

        OpenApiSchemaIndex index = holder.current();
        assertThat(index.domains()).containsExactly("workorder");
        assertThat(index.operationSchemas("workorder_getworkorder")).containsExactly("workorder:WorkorderResponse");
        assertThat(index.operationSchemas("workorder_listworkorders"))
                .containsExactly("workorder:PageWorkorderResponse", "workorder:WorkorderResponse");
        assertThat(index.enums("workorder:WorkorderResponse")).containsKey("status");
    }

    @Test
    @DisplayName("the index is keyed by the very names the mapper persists for the merged aggregate")
    void keyedByPersistedToolNames() {
        OpenApiSchemaIndexHolder holder = new OpenApiSchemaIndexHolder();
        OpenApiDocumentFetcher.DiscoveredOpenApi discovered = fetcher(swaggerConfigClient(), SHADOW, holder)
                .fetchAggregateSpec()
                .block(Duration.ofSeconds(10));
        assertThat(discovered).isNotNull();
        OpenApiToolMapper mapper = new OpenApiToolMapper(properties(), mock(OperationProxyFactory.class));

        List<String> persistedNames = mapper.toDiscoveredOperations("gateway", discovered.openApi()).stream()
                .map(operation -> operation.name())
                .toList();

        assertThat(persistedNames).hasSize(8);
        OpenApiSchemaIndex index = holder.current();
        assertThat(persistedNames)
                .filteredOn(name -> !name.equals("workorder_cancelworkorder"))
                .allSatisfy(name ->
                        assertThat(index.operationSchemas(name)).as(name).isNotEmpty());
    }

    @Test
    @DisplayName("mode off captures nothing, and discovery returns the same aggregate")
    void offCapturesNothing() {
        OpenApiSchemaIndexHolder holder = new OpenApiSchemaIndexHolder();

        OpenApiDocumentFetcher.DiscoveredOpenApi off = fetcher(
                        swaggerConfigClient(), ScopeGraphProperties.off(), holder)
                .fetchAggregateSpec()
                .block(Duration.ofSeconds(10));
        OpenApiDocumentFetcher.DiscoveredOpenApi shadow = fetcher(
                        swaggerConfigClient(), SHADOW, new OpenApiSchemaIndexHolder())
                .fetchAggregateSpec()
                .block(Duration.ofSeconds(10));

        assertThat(holder.current().isEmpty()).isTrue();
        assertThat(off).isNotNull();
        assertThat(shadow).isNotNull();
        assertThat(off.openApi().getPaths().keySet())
                .isEqualTo(shadow.openApi().getPaths().keySet());
        assertThat(off.failedPrefixes()).isEqualTo(shadow.failedPrefixes()).isEmpty();
    }

    @Test
    @DisplayName("a capture that dies, even with an Error, leaves discovery's own result untouched")
    void captureErrorDoesNotFailDiscovery() {
        OpenApiSchemaIndexHolder broken = new OpenApiSchemaIndexHolder() {
            @Override
            public void put(OpenApiSchemaIndex.DomainIndex index) {
                throw new NoClassDefFoundError("simulated");
            }
        };

        OpenApiDocumentFetcher.DiscoveredOpenApi discovered = fetcher(swaggerConfigClient(), SHADOW, broken)
                .fetchAggregateSpec()
                .block(Duration.ofSeconds(10));
        OpenApiDocumentFetcher.DiscoveredOpenApi off = fetcher(
                        swaggerConfigClient(), ScopeGraphProperties.off(), new OpenApiSchemaIndexHolder())
                .fetchAggregateSpec()
                .block(Duration.ofSeconds(10));

        assertThat(discovered).isNotNull();
        assertThat(off).isNotNull();
        assertThat(discovered.openApi().getPaths().keySet())
                .isEqualTo(off.openApi().getPaths().keySet());
        assertThat(discovered.failedPrefixes()).isEmpty();
        assertThat(broken.current().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a single merged aggregate is indexed by the domain of each path")
    void capturesPlainAggregate() {
        OpenApiSchemaIndexHolder holder = new OpenApiSchemaIndexHolder();
        String aggregate = SERVICE_SPEC.replace("  /v1/", "  /workorder/v1/");
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        ClientResponse.create(HttpStatus.OK).body(aggregate).build()))
                .build();

        OpenApiDocumentFetcher.DiscoveredOpenApi discovered =
                fetcher(client, SHADOW, holder).fetchAggregateSpec().block(Duration.ofSeconds(10));

        assertThat(discovered).isNotNull();
        assertThat(holder.current().operationSchemas("workorder_getestimate"))
                .containsExactly("workorder:EstimateResponse", "workorder:EstimateSummaryResponse");
    }

    @Test
    @DisplayName("the per-service Eureka fallback indexes under the routing domain of the service id")
    void capturesEurekaFallback() {
        OpenApiSchemaIndexHolder holder = new OpenApiSchemaIndexHolder();
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        ServiceInstance instance = mock(ServiceInstance.class);
        when(instance.getUri()).thenReturn(URI.create("http://workorder.internal:8080"));
        when(discoveryClient.getInstances("pos-workorder")).thenReturn(List.of(instance));
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        ClientResponse.create(HttpStatus.OK).body(SERVICE_SPEC).build()))
                .build();
        OpenApiDocumentFetcher fetcher =
                new OpenApiDocumentFetcher(discoveryClient, client, properties(), SHADOW, holder);

        assertThat(fetcher.fetchForService("pos-workorder").block(Duration.ofSeconds(10)))
                .isNotNull();

        assertThat(holder.current().domains()).containsExactly("workorder");
        assertThat(holder.current().operationSchemas("workorder_getworkorder"))
                .containsExactly("workorder:WorkorderResponse");
    }

    @Test
    @DisplayName("a failed service fetch keeps the domain's entry from the last cycle that indexed it")
    void failedFetchKeepsPreviousEntry() {
        OpenApiSchemaIndexHolder holder = new OpenApiSchemaIndexHolder();
        fetcher(swaggerConfigClient(), SHADOW, holder).fetchAggregateSpec().block(Duration.ofSeconds(10));
        assertThat(holder.current().domains()).containsExactly("workorder");

        OpenApiDocumentFetcher.DiscoveredOpenApi partial = fetcher(
                        swaggerConfigClient(
                                ClientResponse.create(HttpStatus.NOT_FOUND).build()),
                        SHADOW,
                        holder)
                .fetchAggregateSpec()
                .block(Duration.ofSeconds(10));

        assertThat(partial).isNotNull();
        assertThat(partial.failedPrefixes()).containsExactly("/workorder");
        assertThat(holder.current().operationSchemas("workorder_getworkorder"))
                .containsExactly("workorder:WorkorderResponse");
    }

    private static WebClient swaggerConfigClient() {
        return swaggerConfigClient(
                ClientResponse.create(HttpStatus.OK).body(SERVICE_SPEC).build());
    }

    /** The gateway serves a zero-path aggregate and a swagger-config listing one service, {@code /workorder}. */
    private static WebClient swaggerConfigClient(ClientResponse serviceResponse) {
        ExchangeFunction exchange = request -> Mono.just(
                switch (request.url().getPath()) {
                    case "/v3/api-docs" ->
                        ClientResponse.create(HttpStatus.OK).body("""
                            {"openapi":"3.0.1","info":{"title":"Positivity API Gateway","version":"v1"},"paths":{}}
                            """).build();
                    case "/v3/api-docs/swagger-config" ->
                        ClientResponse.create(HttpStatus.OK).body("""
                            {"urls":[{"url":"/v3/api-docs","name":"gateway"},
                                     {"url":"/workorder/v3/api-docs","name":"workorder"}]}
                            """).build();
                    default -> serviceResponse;
                });
        return WebClient.builder().exchangeFunction(exchange).build();
    }

    private static OpenApiDocumentFetcher fetcher(
            WebClient client, ScopeGraphProperties scopeGraph, OpenApiSchemaIndexHolder holder) {
        return new OpenApiDocumentFetcher(mock(DiscoveryClient.class), client, properties(), scopeGraph, holder);
    }

    private static McpServerProperties properties() {
        return new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                Duration.ofSeconds(5),
                List.of(),
                List.of(),
                AGGREGATE_URL,
                List.of(),
                Map.of());
    }

    private static String read(String resource) {
        try {
            return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
