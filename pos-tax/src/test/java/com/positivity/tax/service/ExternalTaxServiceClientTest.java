package com.positivity.tax.service;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.internal.service.ExternalTaxServiceClient;
import io.github.resilience4j.retry.Retry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Provider-path serialization contract tests for {@link ExternalTaxServiceClient}.
 * <p>
 * Binds a {@link MockRestServiceServer} to the outbound {@link RestClient} and
 * asserts that {@code transactionDate} is serialized onto the request body sent
 * to the external tax provider (the {@code .body(request)} path), mirroring the
 * MockRestServiceServer client-test pattern used elsewhere in the workspace.
 */
@DisplayName("ExternalTaxServiceClient provider-serialization Tests")
class ExternalTaxServiceClientTest {

    private static final String TAX_BASE_URL = "http://tax.test";

    @Test
    @DisplayName("Serializes transactionDate onto the outbound provider request body")
    void shouldSerializeTransactionDateOnOutboundRequest() {
        RestClient.Builder builder = RestClient.builder().baseUrl(TAX_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        ExternalTaxServiceClient client = new ExternalTaxServiceClient(restClient, Retry.ofDefaults("tax-test"));

        server.expect(once(), requestTo(TAX_BASE_URL + "/v1/calculate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.transactionDate").value("2026-02-21T09:30:00Z"))
                .andRespond(withSuccess("{\"testMode\":false}", MediaType.APPLICATION_JSON));

        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(TaxLineItem.builder()
                        .lineItemId("1")
                        .description("Part")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("100"))
                        .build()))
                .destinationAddress(TaxCalculationRequest.TaxAddress.builder()
                        .postalCode("12345")
                        .regionCode("CA")
                        .city("Los Angeles")
                        .countryCode("US")
                        .build())
                .transactionDate("2026-02-21T09:30:00Z")
                .build();

        client.calculateTax(request);

        // Fails if transactionDate was absent/wrong on the outbound body.
        server.verify();
    }
}
