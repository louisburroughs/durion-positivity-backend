package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.enums.TaxCalculationType;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.config.TaxRetryPredicate;
import com.positivity.tax.internal.exception.TaxCalculationException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Story T6-external: {@link AvalaraTaxProvider} maps our request/response DTOs to the Avalara
 * AvaTax REST v2 wire shape, reconciles totals against the Sigma invariant, and honors the
 * ADR-0021 retry policy (retry 5xx, never 4xx). All HTTP is mocked — no live sandbox calls.
 */
@DisplayName("AvalaraTaxProvider Tests")
class AvalaraTaxProviderTest {

    private static final String BASE_URL = "https://sandbox-rest.avatax.com";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-02-21T09:30:00Z"), ZoneOffset.UTC);

    private TaxProperties properties() {
        TaxProperties props = new TaxProperties();
        props.setProvider(TaxProperties.Provider.AVALARA);
        props.getTestMode().setEnabled(false);
        TaxProperties.Avalara avalara = new TaxProperties.Avalara();
        avalara.setCompanyCode("DEFAULT");
        avalara.setBaseUrl(BASE_URL);
        props.setAvalara(avalara);
        return props;
    }

    private Retry retryWithProductionPredicate() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(attempt -> 1L)
                .retryOnException(new TaxRetryPredicate())
                .build();
        return Retry.of("avalara-test", config);
    }

    private record Fixture(AvalaraTaxProvider provider, MockRestServiceServer server) {}

    private Fixture fixture() {
        return fixture(
                Retry.of("avalara-test", RetryConfig.custom().maxAttempts(1).build()));
    }

    private Fixture fixture(Retry retry) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        AvalaraTaxProvider provider = new AvalaraTaxProvider(restClient, retry, properties(), FIXED_CLOCK);
        return new Fixture(provider, server);
    }

    private TaxCalculationRequest sampleRequest() {
        return TaxCalculationRequest.builder()
                .lineItems(List.of(
                        TaxLineItem.builder()
                                .lineItemId("1")
                                .description("Oil Change Service")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("100.00"))
                                .taxCategory("SERVICES")
                                .build(),
                        TaxLineItem.builder()
                                .lineItemId("2")
                                .description("Wiper Blades")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("50.00"))
                                .build()))
                .destinationAddress(TaxAddress.builder()
                        .countryCode("US")
                        .regionCode("CA")
                        .city("Los Angeles")
                        .postalCode("90001")
                        .line1("123 Main St")
                        .build())
                .currencyCode("USD")
                .customerId("CUST-1")
                .referenceId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
                .transactionDate("2026-02-21T09:30:00Z")
                .build();
    }

    @Test
    @DisplayName("providerName is the stable AVALARA label")
    void providerName() {
        assertThat(fixture().provider().providerName()).isEqualTo("AVALARA");
    }

    @Test
    @DisplayName("estimate maps to a non-committing SalesOrder and parses per-line jurisdictions")
    void estimateMapsSalesOrderAndParses() {
        Fixture f = fixture();
        f.server()
                .expect(once(), requestTo(BASE_URL + "/api/v2/transactions/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.type").value("SalesOrder"))
                .andExpect(jsonPath("$.commit").value(false))
                .andExpect(jsonPath("$.companyCode").value("DEFAULT"))
                .andExpect(jsonPath("$.date").value("2026-02-21"))
                .andExpect(jsonPath("$.code").value("550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(jsonPath("$.customerCode").value("CUST-1"))
                .andExpect(jsonPath("$.addresses.ShipTo.region").value("CA"))
                .andExpect(jsonPath("$.addresses.ShipTo.postalCode").value("90001"))
                .andExpect(jsonPath("$.lines[0].number").value("1"))
                .andExpect(jsonPath("$.lines[1].number").value("2"))
                .andRespond(withSuccess(estimateResponseJson(), MediaType.APPLICATION_JSON));

        TaxCalculationResponse response = f.provider().estimate(sampleRequest());
        f.server().verify();

        assertThat(response.isTestMode()).isFalse();
        assertThat(response.getExternalTransactionId()).isEqualTo("987654321");
        assertThat(response.getSubtotal()).isEqualByComparingTo("150.00");
        assertThat(response.getTotalTax()).isEqualByComparingTo("10.88");
        assertThat(response.getTotal()).isEqualByComparingTo("160.88");
        assertThat(response.getCalculationType()).isEqualTo(TaxCalculationType.SALE);
        assertThat(response.getLineItemTaxes()).hasSize(2);
        assertThat(response.getLineItemTaxes().get(0).getLineItemId()).isEqualTo("1");
        assertThat(response.getLineItemTaxes().get(0).getJurisdictions()).hasSize(2);
        assertThat(response.getLineItemTaxes().get(0).getJurisdictions().get(0).getRate())
                .isEqualByComparingTo("0.0600");
        // Top-level jurisdiction aggregation (STATE + COUNTY) with percentage-point rate.
        assertThat(response.getJurisdictions()).hasSize(2);
        assertThat(response.getJurisdictions().get(0).getTaxRate()).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("estimate repairs a cent-level drift so line taxes sum to the AvaTax total (Sigma invariant)")
    void estimateReconcilesDrift() {
        Fixture f = fixture();
        // AvaTax totalTax=10.00 but line taxes sum to 9.99 (drift of +0.01).
        f.server()
                .expect(once(), requestTo(BASE_URL + "/api/v2/transactions/create"))
                .andRespond(withSuccess(driftResponseJson(), MediaType.APPLICATION_JSON));

        TaxCalculationResponse response = f.provider().estimate(sampleRequest());
        f.server().verify();

        assertThat(response.getTotalTax()).isEqualByComparingTo("10.00");
        BigDecimal lineSum = response.getLineItemTaxes().stream()
                .map(TaxCalculationResponse.LineItemTax::getTaxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lineSum).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("refund maps to a ReturnInvoice referencing the original sale, calculationType=REFUND")
    void refundMapsReturnInvoice() {
        Fixture f = fixture();
        UUID original = UUID.fromString("11111111-1111-1111-1111-111111111111");
        f.server()
                .expect(once(), requestTo(BASE_URL + "/api/v2/transactions/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.type").value("ReturnInvoice"))
                .andExpect(jsonPath("$.referenceCode").value(original.toString()))
                .andRespond(withSuccess(estimateResponseJson(), MediaType.APPLICATION_JSON));

        TaxCalculationRequest request = sampleRequest();
        request.setCalculationType(TaxCalculationType.REFUND);
        TaxCalculationResponse response = f.provider().refund(request, original);
        f.server().verify();

        assertThat(response.getCalculationType()).isEqualTo(TaxCalculationType.REFUND);
        assertThat(response.getTotalTax()).isEqualByComparingTo("10.88");
    }

    @Test
    @DisplayName("commit posts commit=true keyed by referenceId and returns COMMITTED with the AvaTax id")
    void commitReturnsCommitted() {
        Fixture f = fixture();
        UUID ref = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        f.server()
                .expect(once(), requestTo(containsString("/api/v2/companies/DEFAULT/transactions/" + ref + "/commit")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.commit").value(true))
                .andRespond(withSuccess(
                        "{\"id\":987654321,\"code\":\"" + ref + "\",\"status\":\"Committed\"}",
                        MediaType.APPLICATION_JSON));

        TaxProviderTransactionResult result = f.provider().commit(ref);
        f.server().verify();

        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        assertThat(result.referenceId()).isEqualTo(ref);
        assertThat(result.externalTransactionId()).isEqualTo("987654321");
    }

    @Test
    @DisplayName("void posts code=DocVoided keyed by referenceId and returns VOIDED")
    void voidReturnsVoided() {
        Fixture f = fixture();
        UUID ref = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        f.server()
                .expect(once(), requestTo(containsString("/api/v2/companies/DEFAULT/transactions/" + ref + "/void")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.code").value("DocVoided"))
                .andRespond(withSuccess(
                        "{\"id\":987654321,\"code\":\"" + ref + "\",\"status\":\"DocVoided\"}",
                        MediaType.APPLICATION_JSON));

        TaxProviderTransactionResult result = f.provider().voidTransaction(ref);
        f.server().verify();

        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.VOIDED);
        assertThat(result.externalTransactionId()).isEqualTo("987654321");
    }

    @Test
    @DisplayName("a 4xx from AvaTax is a deterministic TaxCalculationException, not retried")
    void clientErrorIsNotRetried() {
        Fixture f = fixture(retryWithProductionPredicate());
        f.server()
                .expect(once(), requestTo(BASE_URL + "/api/v2/transactions/create"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"invalid address\"}"));

        assertThatThrownBy(() -> f.provider().estimate(sampleRequest())).isInstanceOf(TaxCalculationException.class);
        // Exactly one call — a 400 is never retried (ADR-0021).
        f.server().verify();
    }

    @Test
    @DisplayName("a 5xx from AvaTax is retried per the shared predicate, then surfaces as TaxCalculationException")
    void serverErrorIsRetried() {
        Fixture f = fixture(retryWithProductionPredicate());
        // Retried up to maxAttempts (3) — each attempt re-hits the mock server.
        f.server()
                .expect(times(3), requestTo(BASE_URL + "/api/v2/transactions/create"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> f.provider().estimate(sampleRequest())).isInstanceOf(TaxCalculationException.class);
        f.server().verify();
    }

    private String estimateResponseJson() {
        return """
                {
                  "id": 987654321,
                  "code": "550e8400-e29b-41d4-a716-446655440000",
                  "status": "Temporary",
                  "totalAmount": 150.00,
                  "totalTax": 10.88,
                  "totalTaxable": 150.00,
                  "lines": [
                    {
                      "lineNumber": "1",
                      "lineAmount": 100.00,
                      "tax": 7.25,
                      "taxableAmount": 100.00,
                      "details": [
                        {"jurisdictionType": "State", "jurisCode": "06", "jurisName": "CALIFORNIA", "rate": 0.06, "tax": 6.00, "taxName": "CA STATE TAX"},
                        {"jurisdictionType": "County", "jurisCode": "037", "jurisName": "LOS ANGELES", "rate": 0.0125, "tax": 1.25, "taxName": "LA COUNTY TAX"}
                      ]
                    },
                    {
                      "lineNumber": "2",
                      "lineAmount": 50.00,
                      "tax": 3.63,
                      "taxableAmount": 50.00,
                      "details": [
                        {"jurisdictionType": "State", "jurisCode": "06", "jurisName": "CALIFORNIA", "rate": 0.06, "tax": 3.00, "taxName": "CA STATE TAX"},
                        {"jurisdictionType": "County", "jurisCode": "037", "jurisName": "LOS ANGELES", "rate": 0.0125, "tax": 0.63, "taxName": "LA COUNTY TAX"}
                      ]
                    }
                  ]
                }
                """;
    }

    private String driftResponseJson() {
        return """
                {
                  "id": 987654321,
                  "code": "550e8400-e29b-41d4-a716-446655440000",
                  "status": "Temporary",
                  "totalAmount": 150.00,
                  "totalTax": 10.00,
                  "totalTaxable": 150.00,
                  "lines": [
                    {
                      "lineNumber": "1",
                      "lineAmount": 100.00,
                      "tax": 6.53,
                      "taxableAmount": 100.00,
                      "details": [
                        {"jurisdictionType": "State", "jurisCode": "06", "jurisName": "CALIFORNIA", "rate": 0.0653, "tax": 6.53, "taxName": "CA STATE TAX"}
                      ]
                    },
                    {
                      "lineNumber": "2",
                      "lineAmount": 50.00,
                      "tax": 3.46,
                      "taxableAmount": 50.00,
                      "details": [
                        {"jurisdictionType": "State", "jurisCode": "06", "jurisName": "CALIFORNIA", "rate": 0.0692, "tax": 3.46, "taxName": "CA STATE TAX"}
                      ]
                    }
                  ]
                }
                """;
    }
}
