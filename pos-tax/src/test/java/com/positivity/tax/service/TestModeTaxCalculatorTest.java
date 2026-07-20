package com.positivity.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.common.enums.TaxCalculationType;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.common.enums.TaxReferenceType;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.service.ActiveCertificateLookup;
import com.positivity.tax.internal.service.ExemptionResolver;
import com.positivity.tax.internal.service.TestModeTaxCalculator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
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
    private StubLookup lookup;

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
        lookup = new StubLookup();
        calculator = new TestModeTaxCalculator(properties, FIXED_CLOCK, new ExemptionResolver(lookup));
    }

    /** Configurable stub certificate lookup: returns {@link #result} for any query. */
    private static final class StubLookup implements ActiveCertificateLookup {
        private Optional<ActiveCertificate> result = Optional.empty();

        void returns(ActiveCertificate certificate) {
            this.result = Optional.of(certificate);
        }

        @Override
        public Optional<ActiveCertificate> findActive(
                String customerId,
                UUID certificateId,
                String stateScope,
                ExemptionReasonCode requestedReason,
                LocalDate date) {
            return result;
        }
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

    // ------------------------------------------------------------------
    // T1: Odoo rounding vectors (single-stage residual distribution)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Odoo vector: 17.79/17.80 split reconciles to 2.49 (naive per-line rounding would drift to 2.50)")
    void shouldReconcile_17_79_and_17_80_split() {
        // Given - single STATE jurisdiction at 7%. Independent per-line rounding
        // gives 1.25 + 1.25 = 2.50, but the true total rounds to 2.49, so the
        // reconciler must remove one cent from the line dimension.
        properties.getTestMode().setDefaultRates(Map.of("STATE", new BigDecimal("0.07")));
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(
                        createLineItem("1", "Line A", "1", "17.79"), createLineItem("2", "Line B", "1", "17.80")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "TX", "Austin", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then - exact known-answer totals
        assertThat(response.getTotalTax()).isEqualByComparingTo("2.49");
        assertThat(response.getJurisdictions()).hasSize(1);
        assertThat(response.getJurisdictions().get(0).getTaxAmount()).isEqualByComparingTo("2.49");
        // Per-line: largest raw (17.80) absorbs the -1c so line 1 stays 1.25, line 2 -> 1.24.
        assertLineTax(response, "1", "1.25");
        assertLineTax(response, "2", "1.24");
        assertLineCellAmounts(response, "1", TaxJurisdictionType.STATE, "1.25");
        assertLineCellAmounts(response, "2", TaxJurisdictionType.STATE, "1.24");
        // Three-way sigma invariant.
        assertSigmaInvariant(response);
    }

    @Test
    @DisplayName("Odoo vector: three-way 33.33/33.33/33.34 reconciles to 7.00 (naive rounding would drift to 6.99)")
    void shouldReconcile_threeWay_33_33_33_34() {
        // Given - single STATE jurisdiction at 7%. Each line raw ~2.333 rounds to
        // 2.33 (sum 6.99); true total rounds to 7.00 so +1c is distributed to the
        // largest raw line (33.34).
        properties.getTestMode().setDefaultRates(Map.of("STATE", new BigDecimal("0.07")));
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(
                        createLineItem("1", "A", "1", "33.33"),
                        createLineItem("2", "B", "1", "33.33"),
                        createLineItem("3", "C", "1", "33.34")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "TX", "Austin", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then
        assertThat(response.getTotalTax()).isEqualByComparingTo("7.00");
        assertThat(response.getJurisdictions()).hasSize(1);
        assertThat(response.getJurisdictions().get(0).getTaxAmount()).isEqualByComparingTo("7.00");
        assertLineTax(response, "1", "2.33");
        assertLineTax(response, "2", "2.33");
        assertLineTax(response, "3", "2.34");
        assertLineCellAmounts(response, "1", TaxJurisdictionType.STATE, "2.33");
        assertLineCellAmounts(response, "2", TaxJurisdictionType.STATE, "2.33");
        assertLineCellAmounts(response, "3", TaxJurisdictionType.STATE, "2.34");
        assertSigmaInvariant(response);
    }

    @Test
    @DisplayName(
            "Odoo vector: odd-priced 4-line cart across STATE/COUNTY/CITY reconciles to 3.74 (naive per-dim rounding drifts to 3.75)")
    void shouldReconcile_multiLine_threeJurisdictions() {
        // Given - STATE 6.25% / COUNTY 1.25% / CITY 0.75% over four odd prices.
        // Both the per-line row sum and the per-jurisdiction column sum of the
        // naively rounded cells drift to 3.75; the reconciler pulls both back to 3.74.
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("STATE", new BigDecimal("0.0625"));
        rates.put("COUNTY", new BigDecimal("0.0125"));
        rates.put("CITY", new BigDecimal("0.0075"));
        properties.getTestMode().setDefaultRates(rates);
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(
                        createLineItem("1", "A", "1", "12.37"),
                        createLineItem("2", "B", "1", "5.19"),
                        createLineItem("3", "C", "1", "7.83"),
                        createLineItem("4", "D", "1", "19.99")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then - grand total
        assertThat(response.getTotalTax()).isEqualByComparingTo("3.74");
        // Per-jurisdiction column totals (STATE, COUNTY, CITY).
        assertThat(response.getJurisdictions()).hasSize(3);
        assertJurisdictionAmount(response, TaxJurisdictionType.STATE, "2.83");
        assertJurisdictionAmount(response, TaxJurisdictionType.COUNTY, "0.57");
        assertJurisdictionAmount(response, TaxJurisdictionType.CITY, "0.34");
        // Per-line totals.
        assertLineTax(response, "1", "1.02");
        assertLineTax(response, "2", "0.43");
        assertLineTax(response, "3", "0.65");
        assertLineTax(response, "4", "1.64");
        // Per-line jurisdiction cell amounts (STATE, COUNTY, CITY order).
        assertLineCellAmounts(response, "1", TaxJurisdictionType.STATE, "0.78");
        assertLineCellAmounts(response, "1", TaxJurisdictionType.COUNTY, "0.15");
        assertLineCellAmounts(response, "1", TaxJurisdictionType.CITY, "0.09");
        assertLineCellAmounts(response, "2", TaxJurisdictionType.STATE, "0.33");
        assertLineCellAmounts(response, "2", TaxJurisdictionType.COUNTY, "0.06");
        assertLineCellAmounts(response, "2", TaxJurisdictionType.CITY, "0.04");
        assertLineCellAmounts(response, "3", TaxJurisdictionType.STATE, "0.49");
        assertLineCellAmounts(response, "3", TaxJurisdictionType.COUNTY, "0.10");
        assertLineCellAmounts(response, "3", TaxJurisdictionType.CITY, "0.06");
        assertLineCellAmounts(response, "4", TaxJurisdictionType.STATE, "1.24");
        assertLineCellAmounts(response, "4", TaxJurisdictionType.COUNTY, "0.25");
        assertLineCellAmounts(response, "4", TaxJurisdictionType.CITY, "0.15");
        assertSigmaInvariant(response);
    }

    // ------------------------------------------------------------------
    // T1: Property-based sigma-invariant (keystone). JUnit5 loop over many
    // randomized carts with a FIXED seed (module has no jqwik on the classpath,
    // so the property is expressed as a deterministic seeded loop).
    // ------------------------------------------------------------------

    /** Fixed seed for reproducibility of the property-based cart generation. */
    private static final long PROPERTY_SEED = 424242L;

    /** Number of randomized carts exercised by the sigma-invariant property. */
    private static final int PROPERTY_ITERATIONS = 2000;

    @Test
    @DisplayName(
            "Property: for every randomized cart, sigma(line taxAmount) == totalTax == sigma(jurisdiction taxAmount), "
                    + "and each line's jurisdiction cells sum to its taxAmount")
    void sigmaInvariantHoldsForRandomizedCarts() {
        Random random = new Random(PROPERTY_SEED);
        String[] typeCodes = {"STATE", "COUNTY", "CITY"};

        for (int iteration = 0; iteration < PROPERTY_ITERATIONS; iteration++) {
            // Random 1..3 jurisdictions, each with a positive decimal-fraction rate.
            int jurisdictionCount = 1 + random.nextInt(3);
            Map<String, BigDecimal> rates = new HashMap<>();
            for (int j = 0; j < jurisdictionCount; j++) {
                // rate in (0, 0.1000], 4-decimal scale.
                BigDecimal rate = BigDecimal.valueOf(1 + random.nextInt(1000), 4);
                rates.put(typeCodes[j], rate);
            }
            properties.getTestMode().setDefaultRates(rates);

            // Random 1..8 lines, cent-scale prices in [0.01, 500.00], ~20% exempt.
            int lineCount = 1 + random.nextInt(8);
            List<TaxLineItem> lines = new ArrayList<>(lineCount);
            for (int l = 0; l < lineCount; l++) {
                BigDecimal price = BigDecimal.valueOf(1 + random.nextInt(50000), 2);
                TaxLineItem item = TaxLineItem.builder()
                        .lineItemId(Integer.toString(l))
                        .description("L" + l)
                        .quantity(BigDecimal.ONE)
                        .unitPrice(price)
                        .subtotal(price)
                        .taxExempt(random.nextInt(5) == 0)
                        .build();
                lines.add(item);
            }

            TaxCalculationRequest request = TaxCalculationRequest.builder()
                    .lineItems(lines)
                    .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                    .build();

            TaxCalculationResponse response = calculator.calculate(request);

            String ctx = "iteration=" + iteration + " seed=" + PROPERTY_SEED;
            BigDecimal totalTax = response.getTotalTax();
            BigDecimal lineSum = sumLineTaxes(response);
            BigDecimal jurisdictionSum = sumJurisdictionAmounts(response);

            assertThat(lineSum)
                    .as("sum(lineItemTaxes.taxAmount) == totalTax [" + ctx + "]")
                    .isEqualByComparingTo(totalTax);
            assertThat(jurisdictionSum)
                    .as("sum(jurisdictions.taxAmount) == totalTax [" + ctx + "]")
                    .isEqualByComparingTo(totalTax);

            for (TaxCalculationResponse.LineItemTax line : response.getLineItemTaxes()) {
                BigDecimal cellSum = line.getJurisdictions().stream()
                        .map(TaxCalculationResponse.JurisdictionTax::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(cellSum)
                        .as("sum(line " + line.getLineItemId() + " jurisdictions[].amount) == line.taxAmount [" + ctx
                                + "]")
                        .isEqualByComparingTo(line.getTaxAmount());
            }
        }
    }

    // ------------------------------------------------------------------
    // T1: effectiveTaxRate over the exempt-filtered taxable base
    // ------------------------------------------------------------------

    @Test
    @DisplayName("effectiveTaxRate divides by the exempt-filtered taxable base, not the raw subtotal")
    void shouldComputeEffectiveTaxRateOnExemptFilteredBase() {
        // Given - combined rate 10% (STATE 8 + COUNTY 1 + CITY 1). One $100 taxable
        // line and one $100 exempt line. Exempt-filtered base = 100 -> rate 10.00%.
        // A (buggy) subtotal-based rate would be 10/200 = 5.00%.
        TaxLineItem taxable = createLineItem("1", "Taxable", "1", "100");
        TaxLineItem exempt = createLineItem("2", "Exempt", "1", "100");
        exempt.setTaxExempt(true);
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(taxable, exempt))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then
        assertThat(response.getSubtotal()).isEqualByComparingTo("200.00");
        assertThat(response.getTotalTax()).isEqualByComparingTo("10.00");
        assertThat(response.getEffectiveTaxRate())
                .as("effectiveTaxRate uses exempt-filtered base (10/100), not subtotal (10/200)")
                .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("effectiveTaxRate is 0.00 for an all-exempt cart (zero-base guard)")
    void shouldReturnZeroEffectiveTaxRateForAllExemptCart() {
        // Given - every line exempt: taxable base is zero.
        TaxLineItem exemptA = createLineItem("1", "Exempt A", "1", "100");
        exemptA.setTaxExempt(true);
        TaxLineItem exemptB = createLineItem("2", "Exempt B", "1", "50");
        exemptB.setTaxExempt(true);
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(exemptA, exemptB))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then
        assertThat(response.getSubtotal()).isEqualByComparingTo("150.00");
        assertThat(response.getTotalTax()).isEqualByComparingTo("0.00");
        assertThat(response.getEffectiveTaxRate()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------
    // T1: Determinism - identical input yields identical output
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Calculating the same cart twice yields identical per-line and per-jurisdiction amounts")
    void shouldBeDeterministicAcrossRepeatedCalculations() {
        // Given - the drift-prone V3 cart, which exercises residual distribution
        // in both the line and jurisdiction dimensions.
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("STATE", new BigDecimal("0.0625"));
        rates.put("COUNTY", new BigDecimal("0.0125"));
        rates.put("CITY", new BigDecimal("0.0075"));
        properties.getTestMode().setDefaultRates(rates);
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(
                        createLineItem("1", "A", "1", "12.37"),
                        createLineItem("2", "B", "1", "5.19"),
                        createLineItem("3", "C", "1", "7.83"),
                        createLineItem("4", "D", "1", "19.99")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse first = calculator.calculate(request);
        TaxCalculationResponse second = calculator.calculate(request);

        // Then - grand total identical.
        assertThat(second.getTotalTax()).isEqualByComparingTo(first.getTotalTax());
        // Jurisdiction column amounts identical (same order, same values, same scale).
        assertThat(second.getJurisdictions()).hasSameSizeAs(first.getJurisdictions());
        for (int j = 0; j < first.getJurisdictions().size(); j++) {
            assertThat(second.getJurisdictions().get(j).getTaxAmount())
                    .isEqualTo(first.getJurisdictions().get(j).getTaxAmount());
        }
        // Per-line and per-cell amounts identical.
        assertThat(second.getLineItemTaxes()).hasSameSizeAs(first.getLineItemTaxes());
        for (int i = 0; i < first.getLineItemTaxes().size(); i++) {
            TaxCalculationResponse.LineItemTax a = first.getLineItemTaxes().get(i);
            TaxCalculationResponse.LineItemTax b = second.getLineItemTaxes().get(i);
            assertThat(b.getTaxAmount()).isEqualTo(a.getTaxAmount());
            assertThat(b.getJurisdictions()).hasSameSizeAs(a.getJurisdictions());
            for (int j = 0; j < a.getJurisdictions().size(); j++) {
                assertThat(b.getJurisdictions().get(j).getAmount())
                        .isEqualTo(a.getJurisdictions().get(j).getAmount());
            }
        }
    }

    // ------------------------------------------------------------------
    // T2: Effective-dated rate-schedule selection
    // ------------------------------------------------------------------

    /**
     * Two-window schedule: STATE 5% from 2024-01-01, STATE 10% from 2024-06-01.
     * A request whose transactionDate falls in each window resolves to that
     * window's rate (different totalTax and jurisdiction breakdown), and the
     * boundary date (transactionDate == effectiveFrom) selects that entry.
     */
    @Test
    @DisplayName("Effective-date selection: transactionDate resolves to the window's rates, boundary date included")
    void shouldSelectRatesByEffectiveDateWindow() {
        configureSchedule(
                scheduleEntry("2024-01-01", Map.of("STATE", new BigDecimal("0.05"))),
                scheduleEntry("2024-06-01", Map.of("STATE", new BigDecimal("0.10"))));

        // Mid window A -> 5%.
        TaxCalculationResponse windowA = calculator.calculate(requestOn("2024-03-15", oneHundredDollarLine()));
        assertThat(windowA.getTotalTax()).isEqualByComparingTo("5.00");
        assertThat(windowA.getJurisdictions()).hasSize(1);
        assertThat(windowA.getJurisdictions().get(0).getTaxRate()).isEqualByComparingTo("5.00");

        // Boundary: transactionDate == effectiveFrom of window B -> that entry applies (10%).
        TaxCalculationResponse boundaryB = calculator.calculate(requestOn("2024-06-01", oneHundredDollarLine()));
        assertThat(boundaryB.getTotalTax()).isEqualByComparingTo("10.00");
        assertThat(boundaryB.getJurisdictions().get(0).getTaxRate()).isEqualByComparingTo("10.00");

        // Mid window B (date-time form also parses to the calendar date) -> 10%.
        TaxCalculationResponse windowB =
                calculator.calculate(requestOn("2024-08-01T09:30:00Z", oneHundredDollarLine()));
        assertThat(windowB.getTotalTax()).isEqualByComparingTo("10.00");

        // Boundary: transactionDate == effectiveFrom of window A -> that entry applies (5%).
        TaxCalculationResponse boundaryA = calculator.calculate(requestOn("2024-01-01", oneHundredDollarLine()));
        assertThat(boundaryA.getTotalTax()).isEqualByComparingTo("5.00");
    }

    /**
     * With a fixed clock (today = 2024-01-01) and a schedule whose first entry is
     * effective on that date, an absent/blank transactionDate selects the entry
     * effective as of the clock's today rather than the flat defaultRates (which
     * would give 10.00 for this cart).
     */
    @Test
    @DisplayName("Absent/blank transactionDate defaults to the clock's today for schedule selection")
    void shouldDefaultAbsentTransactionDateToClockToday() {
        configureSchedule(
                scheduleEntry("2024-01-01", Map.of("STATE", new BigDecimal("0.05"))),
                scheduleEntry("2024-06-01", Map.of("STATE", new BigDecimal("0.10"))));

        // FIXED_CLOCK today == 2024-01-01 -> window A (5%), not defaultRates (10%).
        assertThat(calculator.calculate(requestOn(null, oneHundredDollarLine())).getTotalTax())
                .as("absent transactionDate -> clock today (2024-01-01) -> window A")
                .isEqualByComparingTo("5.00");
        assertThat(calculator.calculate(requestOn("", oneHundredDollarLine())).getTotalTax())
                .as("empty transactionDate -> clock today")
                .isEqualByComparingTo("5.00");
        assertThat(calculator
                        .calculate(requestOn("   ", oneHundredDollarLine()))
                        .getTotalTax())
                .as("blank transactionDate -> clock today")
                .isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("Empty rateSchedule falls back to defaultRates (pre-T2 behavior preserved)")
    void shouldFallBackToDefaultRatesWhenScheduleEmpty() {
        // setUp leaves rateSchedule empty; defaultRates combined = 10%.
        TaxCalculationResponse response = calculator.calculate(requestOn("2024-03-15", oneHundredDollarLine()));

        // Identical to the pre-T2 default-rates behavior: 100 * 0.10 = 10.00 across 3 jurisdictions.
        assertThat(response.getTotalTax()).isEqualByComparingTo("10.00");
        assertThat(response.getJurisdictions()).hasSize(3);
    }

    @Test
    @DisplayName("Schedule present but no entry effective on/before the date falls back to defaultRates")
    void shouldFallBackToDefaultRatesWhenNoEntryQualifies() {
        // Only a future-dated entry; transaction predates it.
        configureSchedule(scheduleEntry("2024-06-01", Map.of("STATE", new BigDecimal("0.05"))));

        TaxCalculationResponse response = calculator.calculate(requestOn("2024-01-01", oneHundredDollarLine()));

        // No effectiveFrom <= 2024-01-01 -> defaultRates (10%) across 3 jurisdictions.
        assertThat(response.getTotalTax()).isEqualByComparingTo("10.00");
        assertThat(response.getJurisdictions()).hasSize(3);
    }

    @Test
    @DisplayName("Unparseable non-blank transactionDate fails fast with IllegalArgumentException (ADR-0021)")
    void shouldFailFastOnUnparseableTransactionDate() {
        assertThatThrownBy(() -> calculator.calculate(requestOn("not-a-date", oneHundredDollarLine())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionDate");

        assertThatThrownBy(() -> calculator.calculate(requestOn("2024-13-99", oneHundredDollarLine())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionDate");
    }

    @Test
    @DisplayName("Sigma-invariant holds for schedule-selected rates over an odd-priced multi-jurisdiction cart")
    void shouldPreserveSigmaInvariantUnderEffectiveDating() {
        Map<String, BigDecimal> scheduledRates = new HashMap<>();
        scheduledRates.put("STATE", new BigDecimal("0.0625"));
        scheduledRates.put("COUNTY", new BigDecimal("0.0125"));
        scheduledRates.put("CITY", new BigDecimal("0.0075"));
        configureSchedule(scheduleEntry("2024-01-01", scheduledRates));

        TaxCalculationRequest request = requestOn(
                "2024-03-01",
                List.of(
                        createLineItem("1", "A", "1", "12.37"),
                        createLineItem("2", "B", "1", "5.19"),
                        createLineItem("3", "C", "1", "7.83"),
                        createLineItem("4", "D", "1", "19.99")));

        TaxCalculationResponse response = calculator.calculate(request);

        // Schedule-selected rates feed the same single-stage matrix/reconciler as T1:
        // the known-answer grand total is 3.74 and all three sigma dimensions reconcile.
        assertThat(response.getTotalTax()).isEqualByComparingTo("3.74");
        assertThat(response.getJurisdictions()).hasSize(3);
        assertSigmaInvariant(response);
    }

    // ------------------------------------------------------------------
    // T3: Exemptions as reportable zero-tax
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T3: certificate-backed exemption yields zero-rate jurisdiction rows with reason echoed")
    void shouldEmitZeroRateRowsForHonoredExemption() {
        // Given - a $100 exempt line (claim: RESALE) backed by a valid certificate, plus a
        // $50 taxable line. Combined rate is 10%.
        lookup.returns(new ActiveCertificateLookup.ActiveCertificate(UUID.randomUUID(), ExemptionReasonCode.RESALE));
        TaxLineItem exempt = TaxLineItem.builder()
                .lineItemId("1")
                .description("Resale part")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100"))
                .taxExempt(true)
                .exemptionReasonCode(ExemptionReasonCode.RESALE)
                .build();
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(exempt, createLineItem("2", "Taxable", "1", "50")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then - only the taxable $50 line contributes: 50 * 0.10 = 5.00.
        assertThat(response.getSubtotal()).isEqualByComparingTo("150.00");
        assertThat(response.getTotalTax()).isEqualByComparingTo("5.00");
        TaxCalculationResponse.LineItemTax exemptLine = findLine(response, "1");
        assertThat(exemptLine.isTaxExempt()).isTrue();
        assertThat(exemptLine.isExemptionDenied()).isFalse();
        assertThat(exemptLine.getExemptionReasonCode()).isEqualTo(ExemptionReasonCode.RESALE);
        assertThat(exemptLine.getTaxAmount()).isEqualByComparingTo("0.00");
        // Zero-rate rows: one per applicable jurisdiction, rate = jurisdiction rate, amount 0.
        assertThat(exemptLine.getJurisdictions()).hasSize(3);
        assertThat(exemptLine.getJurisdictions()).allSatisfy(cell -> {
            assertThat(cell.isExempt()).isTrue();
            assertThat(cell.getExemptionReasonCode()).isEqualTo(ExemptionReasonCode.RESALE);
            assertThat(cell.getAmount()).isEqualByComparingTo("0.00");
            assertThat(cell.getRate()).isGreaterThan(BigDecimal.ZERO);
        });
        assertSigmaInvariant(response);
    }

    @Test
    @DisplayName("T3/D-T2: claimed exemption with no valid certificate is taxed anyway and flagged denied")
    void shouldTaxAndFlagDeniedWhenCertificateMissing() {
        // Given - a $100 line claims GOVERNMENT exemption but the registry returns nothing
        // (stub defaults to empty).
        TaxLineItem claimed = TaxLineItem.builder()
                .lineItemId("1")
                .description("Claimed exempt")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100"))
                .taxExempt(true)
                .exemptionReasonCode(ExemptionReasonCode.GOVERNMENT)
                .build();
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(claimed))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then - taxed normally (never hard-blocked): 100 * 0.10 = 10.00, flagged denied.
        assertThat(response.getTotalTax()).isEqualByComparingTo("10.00");
        TaxCalculationResponse.LineItemTax line = findLine(response, "1");
        assertThat(line.isTaxExempt()).isFalse();
        assertThat(line.isExemptionDenied()).isTrue();
        assertThat(line.getExemptionReasonCode()).isEqualTo(ExemptionReasonCode.GOVERNMENT);
        assertThat(line.getTaxAmount()).isEqualByComparingTo("10.00");
        assertSigmaInvariant(response);
    }

    @Test
    @DisplayName(
            "T3: bare taxExempt with no claim stays intrinsically exempt (backward compatible) with zero-rate rows")
    void shouldKeepBareTaxExemptLineExempt() {
        // Given - taxExempt=true, no reason/certificate/customerExemption; registry empty.
        TaxLineItem bare = TaxLineItem.builder()
                .lineItemId("1")
                .description("Non-taxable")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100"))
                .taxExempt(true)
                .build();
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(bare, createLineItem("2", "Taxable", "1", "100")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        // When
        TaxCalculationResponse response = calculator.calculate(request);

        // Then - only the taxable line contributes: 100 * 0.10 = 10.00.
        assertThat(response.getTotalTax()).isEqualByComparingTo("10.00");
        TaxCalculationResponse.LineItemTax exemptLine = findLine(response, "1");
        assertThat(exemptLine.isTaxExempt()).isTrue();
        assertThat(exemptLine.isExemptionDenied()).isFalse();
        assertThat(exemptLine.getExemptionReasonCode()).isNull();
        assertThat(exemptLine.getJurisdictions()).hasSize(3);
        assertThat(exemptLine.getJurisdictions())
                .allSatisfy(cell -> assertThat(cell.isExempt()).isTrue());
        assertSigmaInvariant(response);
    }

    @Test
    @DisplayName("T3: request-level customerExemption applies to all lines")
    void shouldApplyRequestLevelCustomerExemption() {
        lookup.returns(new ActiveCertificateLookup.ActiveCertificate(UUID.randomUUID(), ExemptionReasonCode.NONPROFIT));
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(createLineItem("1", "A", "1", "100"), createLineItem("2", "B", "1", "40")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .customerId("CUST-1")
                .customerExemption(TaxCalculationRequest.CustomerExemption.builder()
                        .reasonCode(ExemptionReasonCode.NONPROFIT)
                        .build())
                .build();

        TaxCalculationResponse response = calculator.calculate(request);

        // Both lines exempt -> zero total tax, both flagged exempt with reason echoed.
        assertThat(response.getTotalTax()).isEqualByComparingTo("0.00");
        assertThat(response.getLineItemTaxes()).allSatisfy(line -> {
            assertThat(line.isTaxExempt()).isTrue();
            assertThat(line.getExemptionReasonCode()).isEqualTo(ExemptionReasonCode.NONPROFIT);
        });
        assertThat(response.getEffectiveTaxRate()).isEqualByComparingTo("0.00");
        assertSigmaInvariant(response);
    }

    // ------------------------------------------------------------------
    // T4: Refund/credit calculation mode
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T4: REFUND echoes calculationType, prices at the supplied (original) date, amounts positive")
    void shouldComputeRefundModePositiveAtOriginalDate() {
        // Effective-dated schedule: 5% before 2024-06-01, 10% after. A refund priced at the
        // original sale date (2024-03-15) must use 5%, not today's/default rate.
        configureSchedule(
                scheduleEntry("2024-01-01", Map.of("STATE", new BigDecimal("0.05"))),
                scheduleEntry("2024-06-01", Map.of("STATE", new BigDecimal("0.10"))));
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(createLineItem("1", "Returned part", "1", "100")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .transactionDate("2024-03-15")
                .calculationType(TaxCalculationType.REFUND)
                .originalReferenceId(UUID.randomUUID())
                .build();

        TaxCalculationResponse response = calculator.calculate(request);

        assertThat(response.getCalculationType()).isEqualTo(TaxCalculationType.REFUND);
        // Positive amount priced at the original date (5%), callers negate at posting.
        assertThat(response.getTotalTax()).isEqualByComparingTo("5.00");
        assertThat(response.getTotalTax()).isGreaterThan(BigDecimal.ZERO);
        assertSigmaInvariant(response);
    }

    @Test
    @DisplayName("T4: calculationType defaults to SALE and is echoed on the response")
    void shouldDefaultCalculationTypeToSale() {
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of(createLineItem("1", "Part", "1", "100")))
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .build();

        assertThat(calculator.calculate(request).getCalculationType()).isEqualTo(TaxCalculationType.SALE);
    }

    // ------------------------------------------------------------------
    // T7: Config-driven rate resolution in test mode
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T7: two different destination states produce different breakdowns")
    void shouldResolveDifferentRatesPerState() {
        configureJurisdictions(
                rule("CA", null, Map.of("STATE", new BigDecimal("0.0625"), "COUNTY", new BigDecimal("0.0100"))),
                rule("TX", null, Map.of("STATE", new BigDecimal("0.0625"))));

        TaxCalculationResponse ca =
                calculator.calculate(requestForState("CA", List.of(createLineItem("1", "P", "1", "100"))));
        TaxCalculationResponse tx =
                calculator.calculate(requestForState("TX", List.of(createLineItem("1", "P", "1", "100"))));

        // CA: 6.25% + 1% = 7.25% over two jurisdictions; TX: 6.25% over one.
        assertThat(ca.getTotalTax()).isEqualByComparingTo("7.25");
        assertThat(ca.getJurisdictions()).hasSize(2);
        assertThat(tx.getTotalTax()).isEqualByComparingTo("6.25");
        assertThat(tx.getJurisdictions()).hasSize(1);
        assertThat(ca.getTotalTax()).isNotEqualByComparingTo(tx.getTotalTax());
    }

    @Test
    @DisplayName("T7: unknown address falls back to default-rates (current behavior preserved)")
    void shouldFallBackToDefaultRatesForUnknownAddress() {
        configureJurisdictions(rule("CA", null, Map.of("STATE", new BigDecimal("0.0625"))));

        // NY has no matching rule -> default-rates (combined 10%) across 3 jurisdictions.
        TaxCalculationResponse response =
                calculator.calculate(requestForState("NY", List.of(createLineItem("1", "P", "1", "100"))));

        assertThat(response.getTotalTax()).isEqualByComparingTo("10.00");
        assertThat(response.getJurisdictions()).hasSize(3);
    }

    @Test
    @DisplayName("T7: per-category exemption (LABOR exempt in TX) is honored and documented")
    void shouldHonorCategoryExemption() {
        configureJurisdictions(rule("TX", Set.of("LABOR"), Map.of("STATE", new BigDecimal("0.0625"))));

        TaxLineItem labor = TaxLineItem.builder()
                .lineItemId("1")
                .description("Diagnostic labor")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100"))
                .taxCategory("LABOR")
                .build();
        TaxLineItem goods = TaxLineItem.builder()
                .lineItemId("2")
                .description("Oil filter")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100"))
                .taxCategory("GOODS")
                .build();

        TaxCalculationResponse response = calculator.calculate(requestForState("TX", List.of(labor, goods)));

        // Only GOODS taxed: 100 * 0.0625 = 6.25. LABOR is a zero-rate exempt line.
        assertThat(response.getTotalTax()).isEqualByComparingTo("6.25");
        TaxCalculationResponse.LineItemTax laborLine = findLine(response, "1");
        assertThat(laborLine.isTaxExempt()).isTrue();
        assertThat(laborLine.getTaxAmount()).isEqualByComparingTo("0.00");
        assertThat(laborLine.getJurisdictions()).isNotEmpty();
        assertThat(laborLine.getJurisdictions())
                .allSatisfy(cell -> assertThat(cell.isExempt()).isTrue());
        assertThat(findLine(response, "2").getTaxAmount()).isEqualByComparingTo("6.25");
        assertSigmaInvariant(response);
    }

    // Helper methods

    /** Replace the test-mode jurisdiction rules with the given rules. */
    private void configureJurisdictions(TaxProperties.JurisdictionRule... rules) {
        properties.getTestMode().setJurisdictions(new ArrayList<>(List.of(rules)));
    }

    private TaxProperties.JurisdictionRule rule(
            String stateCode, Set<String> exemptCategories, Map<String, BigDecimal> rates) {
        TaxProperties.JurisdictionRule rule = new TaxProperties.JurisdictionRule();
        TaxProperties.JurisdictionMatch match = new TaxProperties.JurisdictionMatch();
        match.setStateCode(stateCode);
        rule.setMatch(match);
        rule.setRates(new HashMap<>(rates));
        if (exemptCategories != null) {
            rule.setExemptCategories(new java.util.LinkedHashSet<>(exemptCategories));
        }
        return rule;
    }

    private TaxCalculationRequest requestForState(String state, List<TaxLineItem> items) {
        return TaxCalculationRequest.builder()
                .lineItems(items)
                .destinationAddress(createAddress(TEST_POSTAL_CODE, state, "Anytown", "US"))
                .build();
    }

    /** Assert the three-way keystone invariant on a single response. */
    private void assertSigmaInvariant(TaxCalculationResponse response) {
        BigDecimal totalTax = response.getTotalTax();
        assertThat(sumLineTaxes(response))
                .as("sum(lineItemTaxes.taxAmount) == totalTax")
                .isEqualByComparingTo(totalTax);
        assertThat(sumJurisdictionAmounts(response))
                .as("sum(jurisdictions.taxAmount) == totalTax")
                .isEqualByComparingTo(totalTax);
        for (TaxCalculationResponse.LineItemTax line : response.getLineItemTaxes()) {
            BigDecimal cellSum = line.getJurisdictions().stream()
                    .map(TaxCalculationResponse.JurisdictionTax::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(cellSum)
                    .as("sum(line " + line.getLineItemId() + " jurisdictions[].amount) == line.taxAmount")
                    .isEqualByComparingTo(line.getTaxAmount());
        }
    }

    private BigDecimal sumLineTaxes(TaxCalculationResponse response) {
        return response.getLineItemTaxes().stream()
                .map(TaxCalculationResponse.LineItemTax::getTaxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumJurisdictionAmounts(TaxCalculationResponse response) {
        return response.getJurisdictions().stream()
                .map(jur -> jur.getTaxAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertLineTax(TaxCalculationResponse response, String lineItemId, String expected) {
        TaxCalculationResponse.LineItemTax line = findLine(response, lineItemId);
        assertThat(line.getTaxAmount()).as("line " + lineItemId + " taxAmount").isEqualByComparingTo(expected);
    }

    private void assertLineCellAmounts(
            TaxCalculationResponse response, String lineItemId, TaxJurisdictionType type, String expected) {
        TaxCalculationResponse.LineItemTax line = findLine(response, lineItemId);
        BigDecimal amount = line.getJurisdictions().stream()
                .filter(cell -> cell.getJurisdictionType() == type)
                .map(TaxCalculationResponse.JurisdictionTax::getAmount)
                .findFirst()
                .orElseThrow(() -> new AssertionError("line " + lineItemId + " has no " + type + " jurisdiction cell"));
        assertThat(amount)
                .as("line " + lineItemId + " " + type + " cell amount")
                .isEqualByComparingTo(expected);
    }

    private void assertJurisdictionAmount(TaxCalculationResponse response, TaxJurisdictionType type, String expected) {
        BigDecimal amount = response.getJurisdictions().stream()
                .filter(jur -> jur.getJurisdictionType() == type)
                .map(jur -> jur.getTaxAmount())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " jurisdiction row"));
        assertThat(amount).as(type + " jurisdiction amount").isEqualByComparingTo(expected);
    }

    private TaxCalculationResponse.LineItemTax findLine(TaxCalculationResponse response, String lineItemId) {
        return response.getLineItemTaxes().stream()
                .filter(line -> lineItemId.equals(line.getLineItemId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line item with id " + lineItemId));
    }

    /** Replace the test-mode rate schedule with the given ordered entries. */
    private void configureSchedule(TaxProperties.RateScheduleEntry... entries) {
        properties.getTestMode().setRateSchedule(new ArrayList<>(List.of(entries)));
    }

    private TaxProperties.RateScheduleEntry scheduleEntry(String effectiveFrom, Map<String, BigDecimal> rates) {
        TaxProperties.RateScheduleEntry entry = new TaxProperties.RateScheduleEntry();
        entry.setEffectiveFrom(LocalDate.parse(effectiveFrom));
        entry.setRates(new HashMap<>(rates));
        return entry;
    }

    /** A single $100 taxable line (subtotal 100.00) for effective-dating window checks. */
    private List<TaxLineItem> oneHundredDollarLine() {
        return List.of(createLineItem("1", "Part", "1", "100"));
    }

    private TaxCalculationRequest requestOn(String transactionDate, List<TaxLineItem> items) {
        return TaxCalculationRequest.builder()
                .lineItems(items)
                .destinationAddress(createAddress(TEST_POSTAL_CODE, "CA", "Los Angeles", "US"))
                .transactionDate(transactionDate)
                .build();
    }

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
