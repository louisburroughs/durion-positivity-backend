package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.config.TaxProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story T6: the test-mode provider must produce exactly the T1 calculator's estimate and
 * expose commit/void as always-succeeding no-ops.
 */
class TestModeTaxProviderTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private TestModeTaxCalculator calculator;
    private TestModeTaxProvider provider;

    @BeforeEach
    void setUp() {
        TaxProperties properties = new TaxProperties();
        TaxProperties.TestMode testMode = new TaxProperties.TestMode();
        testMode.setEnabled(true);
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("STATE", new BigDecimal("0.08"));
        rates.put("COUNTY", new BigDecimal("0.01"));
        rates.put("CITY", new BigDecimal("0.01"));
        testMode.setDefaultRates(rates);
        properties.setTestMode(testMode);

        calculator = new TestModeTaxCalculator(
                FIXED_CLOCK,
                new ExemptionResolver((c, cert, state, reason, date) -> Optional.empty()),
                new TestModeRateResolver(properties));
        provider = new TestModeTaxProvider(calculator);
    }

    @Test
    @DisplayName("providerName is the stable TEST_MODE label")
    void providerName() {
        assertThat(provider.providerName()).isEqualTo("TEST_MODE");
    }

    @Test
    @DisplayName("estimate equals the T1 calculator output")
    void estimateEqualsCalculator() {
        TaxCalculationRequest request = sampleRequest();

        TaxCalculationResponse viaProvider = provider.estimate(request);
        TaxCalculationResponse viaCalculator = calculator.calculate(sampleRequest());

        assertThat(viaProvider.getSubtotal()).isEqualByComparingTo(viaCalculator.getSubtotal());
        assertThat(viaProvider.getTotalTax()).isEqualByComparingTo(viaCalculator.getTotalTax());
        assertThat(viaProvider.getTotal()).isEqualByComparingTo(viaCalculator.getTotal());
        assertThat(viaProvider.getJurisdictions()).hasSameSizeAs(viaCalculator.getJurisdictions());
    }

    @Test
    @DisplayName("commit is a no-op that reports COMMITTED and never fails")
    void commitReportsCommitted() {
        UUID ref = UUID.randomUUID();
        TaxProviderTransactionResult result = provider.commit(ref);
        assertThat(result.referenceId()).isEqualTo(ref);
        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
    }

    @Test
    @DisplayName("void is a no-op that reports VOIDED")
    void voidReportsVoided() {
        UUID ref = UUID.randomUUID();
        TaxProviderTransactionResult result = provider.voidTransaction(ref);
        assertThat(result.referenceId()).isEqualTo(ref);
        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.VOIDED);
    }

    private TaxCalculationRequest sampleRequest() {
        return TaxCalculationRequest.builder()
                .lineItems(List.of(TaxLineItem.builder()
                        .lineItemId("1")
                        .description("Oil change")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("150.00"))
                        .taxExempt(false)
                        .build()))
                .destinationAddress(TaxAddress.builder()
                        .countryCode("US")
                        .regionCode("CA")
                        .city("Los Angeles")
                        .postalCode("90001")
                        .line1("123 Main St")
                        .build())
                .currencyCode("USD")
                .build();
    }
}
