package com.positivity.tax.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.common.enums.TaxReferenceType;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.service.TestModeTaxCalculator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TestModeTaxCalculator Tests")
class TestModeTaxCalculatorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String TEST_POSTAL_CODE = "12345";

    private TaxProperties properties;
    private TestModeTaxCalculator calculator;

    @BeforeEach
    void setUp() {
        properties = new TaxProperties();
        TaxProperties.TestMode testMode = new TaxProperties.TestMode();
        testMode.setEnabled(true);
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("STATE", new BigDecimal("0.08"));
        rates.put("COUNTY", new BigDecimal("0.01"));
        rates.put("CITY", new BigDecimal("0.01"));
        testMode.setDefaultRates(rates);
        properties.setTestMode(testMode);
        calculator = new TestModeTaxCalculator(properties, FIXED_CLOCK);
    }

    @Test
    @DisplayName("Should calculate tax across STATE, COUNTY, and CITY jurisdictions")
    void shouldCalculateTaxAcrossAllJurisdictions() {
        // Given
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(createLineItem("1", "Part", "2", "50")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.isTestMode()).isTrue();
        // subtotal = 2 * 50 = 100
        assertThat(response.getSubtotal()).isEqualByComparingTo("100.00");
        // total tax = 100 * (0.08 + 0.01 + 0.01) = 10.00
        assertThat(response.getTotalTax()).isEqualByComparingTo("10.00");
        assertThat(response.getTotal()).isEqualByComparingTo("110.00");
        // effective rate = 10/100 * 100 = 10.00%
        assertThat(response.getEffectiveTaxRate()).isEqualByComparingTo("10.00");
        assertThat(response.getJurisdictions()).hasSize(3);
    }

    @Test
    @DisplayName("Should exclude tax-exempt items from tax base but include in subtotal")
    void shouldExcludeTaxExemptItemsFromTaxBase() {
        // Given
        TaxLineItem taxableItem = createLineItem("1", "Taxable Part", "1", "100");
        TaxLineItem exemptItem = createLineItem("2", "Exempt Part", "1", "50");
        exemptItem.setTaxExempt(true);

        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(taxableItem, exemptItem))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then
        // Subtotal includes ALL items: 100 + 50 = 150
        assertThat(response.getSubtotal()).isEqualByComparingTo("150.00");
        // Tax base is only taxable item: 100 * 0.10 = 10.00
        assertThat(response.getTotalTax()).isEqualByComparingTo("10.00");
        // Exempt item gets zero tax in line item breakdown
        assertThat(response.getLineItemTaxes()).anySatisfy(lit -> {
            assertThat(lit.getLineItemId()).isEqualTo("2");
            assertThat(lit.isTaxExempt()).isTrue();
            assertThat(lit.getTaxAmount()).isEqualByComparingTo("0.00");
        });
    }

    @Test
    @DisplayName("Should return zero tax and zero effective rate when subtotal is zero")
    void shouldReturnZeroTaxWhenSubtotalIsZero() {
        // Given - item with zero subtotal (manually set)
        TaxLineItem zeroItem = TaxLineItem.builder()
                .lineItemId("1")
                .description("Free Item")
                .quantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.ONE)
                .subtotal(BigDecimal.ZERO)
                .build();

        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(zeroItem))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then
        assertThat(response.getSubtotal()).isEqualByComparingTo("0.00");
        assertThat(response.getTotalTax()).isEqualByComparingTo("0.00");
        assertThat(response.getEffectiveTaxRate()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Should propagate referenceId and referenceType from request to response")
    void shouldPropagateReferenceIdAndReferenceType() {
        // Given
        UUID referenceId = UUID.fromString("00000000-0000-0000-0000-000000000042");
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(createLineItem("1", "Part", "1", "100")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .referenceId(referenceId)
                .referenceType(TaxReferenceType.ESTIMATE)
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then
        assertThat(response.getReferenceId()).isEqualTo(referenceId);
        assertThat(response.getReferenceType()).isEqualTo(TaxReferenceType.ESTIMATE);
    }

    @Test
    @DisplayName("Should use fixed clock for calculatedAt timestamp")
    void shouldUseClockForCalculatedAt() {
        // Given
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(createLineItem("1", "Part", "1", "100")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then
        assertThat(response.getCalculatedAt()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("Should produce one jurisdiction when only state rate is configured")
    void shouldProduceOneJurisdictionWhenOnlyStateRateConfigured() {
        // Given - only state rate
        properties.getTestMode().setDefaultRates(Map.of("STATE", new BigDecimal("0.06")));
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(createLineItem("1", "Part", "1", "200")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "TX", "Austin", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then
        assertThat(response.getJurisdictions()).hasSize(1);
        assertThat(response.getJurisdictions().get(0).getJurisdictionType()).isEqualTo(TaxJurisdictionType.STATE);
        // 200 * 0.06 = 12.00
        assertThat(response.getTotalTax()).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("Should include per-line-item tax breakdown for multiple items")
    void shouldIncludeLineItemTaxBreakdown() {
        // Given
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(createLineItem("1", "Item A", "2", "50"), createLineItem("2", "Item B", "1", "40")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then
        assertThat(response.getLineItemTaxes()).hasSize(2);
        // combined rate = 10% (STATE 8% + COUNTY 1% + CITY 1%)
        // Item A subtotal = 2 * 50 = 100, tax = 10.00
        assertThat(response.getLineItemTaxes()).anySatisfy(lit -> {
            assertThat(lit.getLineItemId()).isEqualTo("1");
            assertThat(lit.getSubtotal()).isEqualByComparingTo("100.00");
            assertThat(lit.getTaxAmount()).isEqualByComparingTo("10.00");
            assertThat(lit.getTotal()).isEqualByComparingTo("110.00");
        });
        // Item B subtotal = 40, tax = 4.00
        assertThat(response.getLineItemTaxes()).anySatisfy(lit -> {
            assertThat(lit.getLineItemId()).isEqualTo("2");
            assertThat(lit.getSubtotal()).isEqualByComparingTo("40.00");
            assertThat(lit.getTaxAmount()).isEqualByComparingTo("4.00");
            assertThat(lit.getTotal()).isEqualByComparingTo("44.00");
        });
    }

    @Test
    @DisplayName("Should return zero jurisdictions and zero tax when no rates configured")
    void shouldReturnZeroJurisdictionsWhenNoRatesConfigured() {
        // Given
        properties.getTestMode().setDefaultRates(new HashMap<>());
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(createLineItem("1", "Part", "1", "100")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then
        assertThat(response.getJurisdictions()).isEmpty();
        assertThat(response.getTotalTax()).isEqualByComparingTo("0.00");
        assertThat(response.getEffectiveTaxRate()).isEqualByComparingTo("0.00");
    }

    // Helper methods

    private TaxCalculationRequest.TaxAddress createAddress(
            String postalCode, String regionCode, String city, String countryCode) {
        return TaxCalculationRequest.TaxAddress.builder()
                .postalCode(postalCode)
                .regionCode(regionCode)
                .city(city)
                .countryCode(countryCode)
                .build();
    }

    private TaxLineItem createLineItem(String id, String desc, String qty, String price) {
        return TaxLineItem.builder()
                .lineItemId(id)
                .description(desc)
                .quantity(new BigDecimal(qty))
                .unitPrice(new BigDecimal(price))
                .build();
    }
}
