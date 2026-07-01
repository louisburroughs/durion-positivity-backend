package com.positivity.mcp.internal.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.discovery.OpenApiDocumentFetcher.ServiceDoc;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class OpenApiDocumentFetcherSwaggerConfigTest {

    @Test
    @DisplayName("parseServiceDocs returns per-service docs with routing prefixes, skipping the gateway's own spec")
    void parseServiceDocs_extractsServicesAndPrefixes_skipsGateway() {
        String json = """
                {"configUrl":"/v3/api-docs/swagger-config",
                 "urls":[
                   {"url":"/accounting/v3/api-docs","name":"Accounting"},
                   {"url":"/v3/api-docs","name":"Gateway"},
                   {"url":"/catalog/v3/api-docs","name":"Catalog"}
                 ]}
                """;

        List<ServiceDoc> docs = OpenApiDocumentFetcher.parseServiceDocs(json, "/v3/api-docs");

        assertThat(docs)
                .extracting(ServiceDoc::url, ServiceDoc::routingPrefix)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("/accounting/v3/api-docs", "/accounting"),
                        org.assertj.core.groups.Tuple.tuple("/catalog/v3/api-docs", "/catalog"));
    }

    @Test
    @DisplayName("parseServiceDocs returns empty for malformed JSON or missing urls array")
    void parseServiceDocs_malformedOrEmpty_returnsEmpty() {
        assertThat(OpenApiDocumentFetcher.parseServiceDocs("not json", "/v3/api-docs"))
                .isEmpty();
        assertThat(OpenApiDocumentFetcher.parseServiceDocs("{}", "/v3/api-docs"))
                .isEmpty();
    }

    @Test
    @DisplayName("prefixPaths prepends the routing segment to each service-local path")
    void prefixPaths_prependsRoutingSegment() {
        OpenAPI serviceSpec = new OpenAPI()
                .paths(new Paths()
                        .addPathItem("/v1/accounting/invoices", new PathItem())
                        .addPathItem("/v1/accounting/journal-entries/{id}", new PathItem()));

        Paths prefixed = OpenApiDocumentFetcher.prefixPaths(serviceSpec, "/accounting");

        assertThat(prefixed.keySet())
                .containsExactlyInAnyOrder(
                        "/accounting/v1/accounting/invoices", "/accounting/v1/accounting/journal-entries/{id}");
    }

    @Test
    @DisplayName("prefixPaths tolerates a null spec or null paths")
    void prefixPaths_nullSafe() {
        assertThat(OpenApiDocumentFetcher.prefixPaths(null, "/accounting")).isEmpty();
        assertThat(OpenApiDocumentFetcher.prefixPaths(new OpenAPI(), "/accounting"))
                .isEmpty();
    }

    @Test
    @DisplayName("isTransient retries timeouts and 5xx (cold service), not 4xx or non-network errors")
    void isTransient_classifiesRetryableFailures() {
        assertThat(OpenApiDocumentFetcher.isTransient(new TimeoutException())).isTrue();
        assertThat(OpenApiDocumentFetcher.isTransient(
                        new WebClientResponseException(503, "Service Unavailable", HttpHeaders.EMPTY, null, null)))
                .isTrue();
        assertThat(OpenApiDocumentFetcher.isTransient(
                        new WebClientResponseException(404, "Not Found", HttpHeaders.EMPTY, null, null)))
                .isFalse();
        assertThat(OpenApiDocumentFetcher.isTransient(new IllegalStateException("boom")))
                .isFalse();
    }
}
