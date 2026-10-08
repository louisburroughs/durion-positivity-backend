package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.internal.config.TaxProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * CAP:550 S32a AC 3 (ADR-0044 §3): with a country profile configured, a US address answers exactly
 * today's JSON for {@code /rates} and {@code /calculate}, apart from the two new null fields
 * {@code taxType} and {@code inputTaxRecoverable}.
 */
@DisplayName("US tax contract unchanged apart from two null fields (CAP:550 S32a)")
class UsTaxContractTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final TaxProperties properties = TaxProfileFixtures.bind(TaxProfileFixtures.FIRST_COUNTRY);
    private final TaxCountryProfiles profiles = new TaxCountryProfiles(properties);
    private final TestModeRateResolver rateResolver = new TestModeRateResolver(properties);
    private final TaxProviderSelector selector = new TaxProviderSelector(
            properties,
            new TestModeTaxProvider(new TestModeTaxCalculator(
                    CLOCK,
                    new ExemptionResolver((customer, certificate, state, reason, date) -> Optional.empty()),
                    rateResolver)),
            new ExternalTaxProvider(mock(ExternalTaxServiceClient.class)),
            mock(AvalaraTaxProvider.class),
            profiles,
            CLOCK);

    @Test
    @DisplayName("/rates for a US address: today's JSON plus null taxType and inputTaxRecoverable")
    void usRatesJson() throws Exception {
        TaxRateLookupServiceImpl rates =
                new TaxRateLookupServiceImpl(properties, selector, rateResolver, profiles, CLOCK);

        String json = MAPPER.writeValueAsString(
                rates.lookupRates("US", "CA", "Los Angeles", "90001", LocalDate.parse("2026-08-27")));

        JSONAssert.assertEquals("""
                {"countryCode":"US","regionCode":"CA","city":"Los Angeles","postalCode":"90001","asOf":"2026-08-27",
                 "components":[{"jurisdictionType":"STATE","rate":0.0725,"taxType":null,"inputTaxRecoverable":null}],
                 "combinedRate":0.0725,"source":"TEST_MODE"}
                """, json, true);
    }

    @Test
    @DisplayName("/calculate for a US address: today's fields and values plus two null fields per line row")
    void usCalculateJson() throws Exception {
        TaxCalculationServiceImpl calc =
                new TaxCalculationServiceImpl(properties, selector, mock(TaxProviderLifecycleService.class));
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(TaxLineItem.builder()
                        .lineItemId("1")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("100.00"))
                        .build()))
                .destinationAddress(TaxCalculationRequest.TaxAddress.builder()
                        .countryCode("US")
                        .regionCode("CA")
                        .postalCode("90001")
                        .build())
                .build();

        TaxCalculationResponse response = calc.calculateTax(request);
        JsonNode tree = MAPPER.readTree(MAPPER.writeValueAsString(response));

        List<String> topLevel =
                tree.properties().stream().map(Map.Entry::getKey).toList();
        assertThat(topLevel)
                .containsExactlyInAnyOrder(
                        "subtotal",
                        "totalTax",
                        "total",
                        "effectiveTaxRate",
                        "jurisdictions",
                        "lineItemTaxes",
                        "testMode",
                        "calculatedAt",
                        "referenceId",
                        "referenceType",
                        "externalTransactionId",
                        "calculationType",
                        "originalReferenceId");
        JSONAssert.assertEquals(
                """
                {"jurisdictionType":"STATE","code":"STATE","rate":0.0725,"amount":7.25,"exempt":false,
                 "exemptionReasonCode":null,"taxType":null,"inputTaxRecoverable":null}
                """,
                MAPPER.writeValueAsString(
                        tree.get("lineItemTaxes").get(0).get("jurisdictions").get(0)),
                true);
        assertThat(tree.get("totalTax").decimalValue()).isEqualByComparingTo("7.25");
        assertThat(tree.get("testMode").booleanValue()).isTrue();
    }
}
