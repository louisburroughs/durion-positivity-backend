package com.positivity.supplier.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.supplier.internal.exception.SupplierServiceUnavailableException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The pos-tax registration-schemes read (#2623 tests): success, every failure answers 503 and writes nothing, the
 * WARN line carries the path and status only.
 */
@DisplayName("TaxRegistrationSchemeClient — pos-tax's registration-scheme list, fail-closed (#2623)")
class TaxRegistrationSchemeClientTest {

    private static final String BODY = """
            {"source":"STUB","schemes":[
              {"scheme":"GST_HST","countryCode":"CA","label":"GST/HST","holderType":"BUSINESS","regional":false,"regions":[]},
              {"scheme":"QST","countryCode":"CA","label":"QST","holderType":"BUSINESS","regional":true,"regions":["QC"]}
            ]}
            """;

    private static final java.time.Clock CLOCK =
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-17T12:00:00Z"), java.time.ZoneOffset.UTC);

    private MockRestServiceServer server;
    private TaxRegistrationSchemeClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TaxRegistrationSchemeClient(
                builder, "pos-tax", "/v1/tax/registration-schemes", java.time.Duration.ZERO, CLOCK);
    }


    @Test
    @DisplayName("reads the list with the service user and tax:rates:view, by country or for every country")
    void readsTheList() {
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes?countryCode=CA"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-supplier-service"))
                .andExpect(header("X-Authorities", "tax:rates:view"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        TaxRegistrationSchemes ca = client.fetch("CA");
        assertThat(ca.source()).isEqualTo("STUB");
        assertThat(ca.byCode()).containsOnlyKeys("GST_HST", "QST");
        assertThat(ca.byCode().get("QST").regional()).isTrue();
        assertThat(ca.byCode().get("QST").regions()).containsExactly("QC");
        assertThat(client.fetch(null).schemes()).hasSize(2);
        server.verify();
    }

    @Test
    @DisplayName(
            "the full list is cached for the TTL; a country read and an expired cache read pos-tax again; a failure never fills it")
    void cachesTheFullList() {
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-10-17T12:00:00Z"));
        java.time.Clock ticking = new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                return now.get();
            }
        };
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer cached = MockRestServiceServer.bindTo(builder).build();
        TaxRegistrationSchemeClient caching = new TaxRegistrationSchemeClient(
                builder, "pos-tax", "/v1/tax/registration-schemes", java.time.Duration.ofSeconds(60), ticking);
        cached.expect(requestTo("http://pos-tax/v1/tax/registration-schemes")).andRespond(withServerError());
        cached.expect(requestTo("http://pos-tax/v1/tax/registration-schemes"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        cached.expect(requestTo("http://pos-tax/v1/tax/registration-schemes?countryCode=CA"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        cached.expect(requestTo("http://pos-tax/v1/tax/registration-schemes"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> caching.fetch(null)).isInstanceOf(SupplierServiceUnavailableException.class);
        assertThat(caching.fetch(null).schemes()).hasSize(2);
        assertThat(caching.fetch(null).schemes()).as("served from the cache").hasSize(2);
        assertThat(caching.fetch("CA").schemes())
                .as("a country read is not cached")
                .hasSize(2);
        now.set(now.get().plusSeconds(61));
        assertThat(caching.fetch(null).schemes()).as("expired: read again").hasSize(2);
        cached.verify();
    }

    @Test
    @DisplayName("the call carries the bound tenant and the correlation id")
    void forwardsTenantAndCorrelation() {
        java.util.UUID tenant = java.util.UUID.fromString("019a0000-0000-7000-8000-000000002623");
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes"))
                .andExpect(header(com.positivity.tenancy.TenantHeaders.HTTP_TENANT_ID, tenant.toString()))
                .andExpect(header("X-Correlation-Id", "corr-2623"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        com.positivity.tenancy.TenantContext.bind(tenant);
        try {
            com.positivity.supplier.internal.audit.SupplierCorrelationContext.withCorrelationId(
                    "corr-2623", () -> assertThat(client.fetch(null).schemes()).hasSize(2));
        } finally {
            com.positivity.tenancy.TenantContext.clear();
        }
        server.verify();
    }

    @Test
    @DisplayName("a 5xx, a 4xx, an empty body or an unreachable pos-tax is SupplierServiceUnavailableException")
    void failsClosed() {
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes")).andRespond(withServerError());
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes")).andRespond(request -> {
            throw new SocketTimeoutException("read timed out");
        });

        java.util.List<String> messages = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.fetch(null))
                    .isInstanceOf(SupplierServiceUnavailableException.class)
                    .satisfies(e -> {
                        assertThat(((SupplierServiceUnavailableException) e).getCode()).isEqualTo("SERVICE_UNAVAILABLE");
                        messages.add(e.getMessage());
                    });
        }
        server.verify();
        // What the caller sees names the dependency and the status only, never pos-tax's body (ADR-0072).
        String said = String.join(" ", messages);
        assertThat(said).contains("500").contains("400").contains("no registration-scheme list").contains("reached");
        assertThat(said).doesNotContain("schemes\"").doesNotContain("GST_HST");
    }
}
