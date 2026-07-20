package com.positivity.workorder.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxLineItem;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link TaxClient} wire behavior against a {@link MockRestServiceServer}
 * (same lightweight pattern as pos-warranty's {@code InvoiceClientTest}).
 * <p>
 * pos-tax guards {@code /v1/tax/calculate} with {@code @PreAuthorize("tax:calculate")},
 * so this service-to-service call must propagate the gateway authority headers
 * (mirrors the pos-invoice {@code TaxServiceClient} pattern).
 */
class TaxClientTest {

    private static final String BASE = "http://pos-tax:8091";

    private MockRestServiceServer server;
    private TaxClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TaxClient(builder.build());
    }

    @Test
    void calculateTaxPropagatesServiceAuthorityHeadersAndMapsResponse() {
        server.expect(requestTo(BASE + "/v1/tax/calculate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-workorder"))
                .andExpect(header("X-Authorities", "tax:calculate"))
                .andRespond(withSuccess(
                        "{\"subtotal\":150.00,\"totalTax\":15.00,\"total\":165.00,"
                                + "\"effectiveTaxRate\":10.00,\"testMode\":false}",
                        MediaType.APPLICATION_JSON));

        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(TaxLineItem.builder()
                        .lineItemId("1")
                        .description("Oil Change Service")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("150.00"))
                        .build()))
                .destinationAddress(TaxCalculationRequest.TaxAddress.builder()
                        .countryCode("US")
                        .regionCode("CA")
                        .build())
                .currencyCode("USD")
                .build();

        TaxCalculationResponse response = client.calculateTax(request);

        assertThat(response).isNotNull();
        assertThat(response.getSubtotal()).isEqualByComparingTo("150.00");
        assertThat(response.getTotalTax()).isEqualByComparingTo("15.00");
        assertThat(response.getTotal()).isEqualByComparingTo("165.00");
        server.verify();
    }
}
