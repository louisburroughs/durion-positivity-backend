package com.positivity.workorder.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Wire-contract pinning for the labor-time edge caller (#1569): field names must match
 * pos-catalog's {@code LaborTimeQuoteRequest}/{@code LaborTimeQuoteResponse}, the internal
 * authority header must ride every call, and every failure mode is an empty answer — the
 * degradation contract the ADR-0044 amendment requires of this class.
 */
@DisplayName("CatalogLaborTimeClientImpl")
class CatalogLaborTimeClientImplTest {

    private static final UUID SERVICE_ID = UUID.fromString("56b14899-cb6c-7628-0763-4c603ec0a325");

    private MockRestServiceServer server;
    private CatalogLaborTimeClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CatalogLaborTimeClientImpl(builder, "catalog");
    }

    @Test
    @DisplayName("posts the quote request with the service authority and maps a RESOLVED answer")
    void resolvedAnswerMapped() {
        server.expect(requestTo("http://catalog/v1/catalog/labor-times/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Authorities", "catalog:labor_time:resolve"))
                .andExpect(jsonPath("$.serviceId").value(SERVICE_ID.toString()))
                .andExpect(jsonPath("$.make").value("Honda"))
                .andRespond(withSuccess("""
                        {"status":"RESOLVED","laborHours":1.5,"timeType":"RETAIL_FLAT_RATE",
                         "sourceCode":"MOCKGUIDE","sourceRevision":"2026-09-01","matchGrade":"EXACT",
                         "overlapGroup":"WHEEL-OFF","includedOpCodes":["BRAKE-PAD-FRONT"]}
                        """, MediaType.APPLICATION_JSON));

        var time = client.resolveLaborTime(SERVICE_ID, "2019-2023", "Honda", "Civic")
                .orElseThrow();

        assertThat(time.laborHours()).isEqualByComparingTo("1.5");
        assertThat(time.sourceCode()).isEqualTo("MOCKGUIDE");
        assertThat(time.overlapGroup()).isEqualTo("WHEEL-OFF");
        assertThat(time.includedOpCodes()).containsExactly("BRAKE-PAD-FRONT");
        server.verify();
    }

    @Test
    @DisplayName("a typed miss from the edge is an empty answer, not a partial one")
    void typedMissIsEmpty() {
        server.expect(requestTo("http://catalog/v1/catalog/labor-times/resolve"))
                .andRespond(withSuccess("""
                        {"status":"NO_TIME_AVAILABLE","includedOpCodes":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.resolveLaborTime(SERVICE_ID, null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("a 5xx from the edge is an empty answer — estimating never fails over the guide")
    void serverErrorIsEmpty() {
        server.expect(requestTo("http://catalog/v1/catalog/labor-times/resolve"))
                .andRespond(withServerError());

        assertThat(client.resolveLaborTime(SERVICE_ID, null, null, null)).isEmpty();
    }
}
