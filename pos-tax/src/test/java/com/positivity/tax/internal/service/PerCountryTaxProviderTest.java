package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxCalculationResponse.JurisdictionTax;
import com.positivity.tax.common.dto.TaxCalculationResponse.LineItemTax;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.dto.TaxRateComponent;
import com.positivity.tax.common.dto.TaxRateLookupResponse;
import com.positivity.tax.common.dto.TaxTypesResponse;
import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.common.enums.TaxCalculationType;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.common.enums.TaxReferenceType;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.exception.TaxJurisdictionNotConfiguredException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * CAP:550 S32a ACs 1, 2, 4 and 5: the per-country default routes a profiled country to its
 * configuration-driven plug-in in every provider mode; rates and calculation come from the
 * country's fixture rows (never the US defaults); a made-up country works from configuration alone;
 * every other country keeps today's switch; the tax-types read projects the profile.
 * <p>
 * All rates are fixtures from {@link TaxProfileFixtures}: <strong>not tax law</strong>.
 */
@DisplayName("Per-country default tax provider (CAP:550 S32a)")
class PerCountryTaxProviderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate AS_OF = LocalDate.parse("2026-08-27");

    private final ExternalTaxServiceClient externalClient = mock(ExternalTaxServiceClient.class);
    private final AvalaraTaxProvider avalara = mock(AvalaraTaxProvider.class);
    private final TaxProviderLifecycleService lifecycle = mock(TaxProviderLifecycleService.class);

    /** The provider modes of today's deployment-wide switch. */
    static Stream<Arguments> providerModes() {
        return Stream.of(
                Arguments.of("test mode", true, TaxProperties.Provider.TEST_MODE),
                Arguments.of("Avalara", false, TaxProperties.Provider.AVALARA),
                Arguments.of("legacy external", false, TaxProperties.Provider.EXTERNAL));
    }

    private record Services(
            TaxProviderSelector selector, TaxRateLookupServiceImpl rates, TaxCalculationServiceImpl calc) {}

    @SafeVarargs
    private Services services(boolean testMode, TaxProperties.Provider provider, Map<String, String>... fixtures) {
        TaxProperties properties = TaxProfileFixtures.bind(fixtures);
        properties.getTestMode().setEnabled(testMode);
        properties.setProvider(provider);
        TaxCountryProfiles profiles = new TaxCountryProfiles(properties);
        TestModeRateResolver rateResolver = new TestModeRateResolver(properties);
        TestModeTaxCalculator calculator = new TestModeTaxCalculator(
                CLOCK,
                new ExemptionResolver((customer, certificate, state, reason, date) -> Optional.empty()),
                rateResolver);
        TaxProviderSelector selector = new TaxProviderSelector(
                properties,
                new TestModeTaxProvider(calculator),
                new ExternalTaxProvider(externalClient),
                avalara,
                profiles,
                CLOCK);
        return new Services(
                selector,
                new TaxRateLookupServiceImpl(properties, selector, rateResolver, profiles, CLOCK),
                new TaxCalculationServiceImpl(properties, selector, lifecycle));
    }

    private Services firstCountry() {
        return services(true, TaxProperties.Provider.TEST_MODE, TaxProfileFixtures.FIRST_COUNTRY);
    }

    private static TaxLineItem line(String id, String amount) {
        return TaxLineItem.builder()
                .lineItemId(id)
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal(amount))
                .build();
    }

    private static TaxCalculationRequest request(String country, String region, String date, TaxLineItem... lines) {
        return TaxCalculationRequest.builder()
                .lineItems(List.of(lines))
                .destinationAddress(TaxCalculationRequest.TaxAddress.builder()
                        .countryCode(country)
                        .regionCode(region)
                        .postalCode("A1A1A1")
                        .build())
                .transactionDate(date)
                // Fixture currencies: the profile's own for each fixture country (not tax law).
                .currencyCode(
                        switch (country) {
                            case "CA" -> "CAD";
                            case "ZZ" -> "JPY";
                            default -> "USD";
                        })
                .build();
    }

    private static JurisdictionTax cell(LineItemTax line, String type) {
        return line.getJurisdictions().stream()
                .filter(j -> type.equals(j.getTaxType()))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    @DisplayName("rate lookup (AC 1)")
    class Rates {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.positivity.tax.internal.service.PerCountryTaxProviderTest#providerModes")
        @DisplayName("a profiled country answers typed components from its rows in every provider mode")
        void typedComponentsInEveryMode(String mode, boolean testMode, TaxProperties.Provider provider) {
            Services s = services(testMode, provider, TaxProfileFixtures.FIRST_COUNTRY);

            TaxRateLookupResponse response = s.rates().lookupRates("CA", "BC", null, "A1A1A1", AS_OF);

            assertThat(response.source()).isEqualTo("STUB");
            assertThat(response.components())
                    .containsExactlyInAnyOrder(
                            new TaxRateComponent(TaxJurisdictionType.COUNTRY, new BigDecimal("0.012"), "GST", true),
                            new TaxRateComponent(TaxJurisdictionType.PROVINCE, new BigDecimal("0.022"), "PST", false));
            assertThat(response.combinedRate()).isEqualByComparingTo("0.034");
            verifyNoInteractions(avalara, externalClient);
        }

        @Test
        @DisplayName("[M] a profiled-country address is never priced from the US default rates")
        void neverFallsBackToUsDefaults() {
            TaxRateLookupResponse response = firstCountry().rates().lookupRates("CA", "QC", null, "A1A1A1", AS_OF);

            // The US default-rates fixture is STATE 0.0725; a fallback would answer exactly that.
            assertThat(response.components())
                    .extracting(TaxRateComponent::jurisdictionType)
                    .doesNotContain(TaxJurisdictionType.STATE);
            assertThat(response.components())
                    .extracting(TaxRateComponent::taxType)
                    .containsExactlyInAnyOrder("GST", "QST");
            assertThat(response.source()).isEqualTo("STUB");
        }

        @Test
        @DisplayName("a region with no row on the date answers TAX_JURISDICTION_NOT_CONFIGURED")
        void regionWithoutRowIsRefused() {
            Services s = firstCountry();

            assertThatThrownBy(() -> s.rates().lookupRates("CA", "AB", null, "A1A1A1", AS_OF))
                    .isInstanceOf(TaxJurisdictionNotConfiguredException.class);
            assertThatThrownBy(() -> s.rates().lookupRates("CA", null, null, "A1A1A1", AS_OF))
                    .isInstanceOf(TaxJurisdictionNotConfiguredException.class);
            assertThatThrownBy(() -> s.rates().lookupRates("CA", "BC", null, "A1A1A1", LocalDate.parse("2019-01-01")))
                    .isInstanceOf(TaxJurisdictionNotConfiguredException.class);
        }

        @Test
        @DisplayName("the rows in effect follow the date: the rate changes on its effective-from")
        void rowsFollowTheDate() {
            Services s = firstCountry();

            assertThat(s.rates()
                            .lookupRates("CA", "BC", null, "A1A1A1", LocalDate.parse("2026-06-30"))
                            .combinedRate())
                    .isEqualByComparingTo("0.033");
            assertThat(s.rates()
                            .lookupRates("CA", "BC", null, "A1A1A1", LocalDate.parse("2026-07-01"))
                            .combinedRate())
                    .isEqualByComparingTo("0.034");
        }
    }

    @Nested
    @DisplayName("calculation (AC 1, AC 4)")
    class Calculation {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.positivity.tax.internal.service.PerCountryTaxProviderTest#providerModes")
        @DisplayName(
                "a profiled destination is priced by its plug-in in every provider mode, one typed row per tax type")
        void typedRowsInEveryMode(String mode, boolean testMode, TaxProperties.Provider provider) {
            Services s = services(testMode, provider, TaxProfileFixtures.FIRST_COUNTRY);

            TaxCalculationResponse response =
                    s.calc().calculateTax(request("CA", "BC", "2026-08-27", line("1", "100.00"), line("2", "33.33")));

            LineItemTax first = response.getLineItemTaxes().get(0);
            assertThat(cell(first, "GST").getAmount()).isEqualByComparingTo("1.20");
            assertThat(cell(first, "GST").getCode()).isEqualTo("CA");
            assertThat(cell(first, "GST").getInputTaxRecoverable()).isTrue();
            assertThat(cell(first, "PST").getAmount()).isEqualByComparingTo("2.20");
            assertThat(cell(first, "PST").getCode()).isEqualTo("BC");
            assertThat(cell(first, "PST").getInputTaxRecoverable()).isFalse();
            // HALF_UP at the currency exponent, per row: 33.33 x 0.012 = 0.39996 -> 0.40; x 0.022 = 0.73326 -> 0.73.
            LineItemTax second = response.getLineItemTaxes().get(1);
            assertThat(cell(second, "GST").getAmount()).isEqualByComparingTo("0.40");
            assertThat(cell(second, "PST").getAmount()).isEqualByComparingTo("0.73");
            assertThat(second.getTaxAmount()).isEqualByComparingTo("1.13");
            // Totals are sums of the rounded rows.
            assertThat(response.getTotalTax()).isEqualByComparingTo("4.53");
            assertThat(response.getJurisdictions())
                    .extracting(j -> j.getTaxAmount().toPlainString())
                    .containsExactlyInAnyOrder("1.60", "2.93");
            verifyNoInteractions(avalara, externalClient);
        }

        @Test
        @DisplayName("a destination with no row answers TAX_JURISDICTION_NOT_CONFIGURED, never the US rates")
        void destinationWithoutRowIsRefused() {
            Services s = firstCountry();

            assertThatThrownBy(() -> s.calc().calculateTax(request("CA", "AB", "2026-08-27", line("1", "10.00"))))
                    .isInstanceOf(TaxJurisdictionNotConfiguredException.class);
        }

        @Test
        @DisplayName(
                "a request in another currency than the profile's is refused (ADR-0067 PC-9), never rounded under it")
        void otherCurrencyIsRefused() {
            TaxCalculationRequest usd = request("CA", "BC", "2026-08-27", line("1", "100.00"));
            usd.setCurrencyCode("USD");

            assertThatThrownBy(() -> firstCountry().calc().calculateTax(usd))
                    .isInstanceOf(com.positivity.tax.internal.exception.TaxCurrencyNotSupportedException.class)
                    .hasMessageContaining("CAD");
        }

        @Test
        @DisplayName("REFUND uses the same rows, priced at the supplied transaction date")
        void refundUsesTheSameRows() {
            TaxCalculationRequest refund = request("CA", "BC", "2026-06-30", line("1", "100.00"));
            refund.setCalculationType(TaxCalculationType.REFUND);
            refund.setOriginalReferenceId(UUID.fromString("00000000-0000-7000-8000-0000000000aa"));

            TaxCalculationResponse response = firstCountry().calc().calculateTax(refund);

            assertThat(response.getCalculationType()).isEqualTo(TaxCalculationType.REFUND);
            assertThat(response.getOriginalReferenceId()).isEqualTo(refund.getOriginalReferenceId());
            assertThat(cell(response.getLineItemTaxes().get(0), "GST").getAmount())
                    .isEqualByComparingTo("1.10");
        }

        @Test
        @DisplayName("an exemption claim is taxed and flagged; a bare taxExempt line is taxed zero")
        void exemptionClaimIsTaxedAndFlagged() {
            TaxLineItem claimed = line("1", "100.00");
            claimed.setExemptionReasonCode(ExemptionReasonCode.RESALE);
            TaxLineItem bareExempt = line("2", "50.00");
            bareExempt.setTaxExempt(true);

            TaxCalculationResponse response =
                    firstCountry().calc().calculateTax(request("CA", "ON", "2026-08-27", claimed, bareExempt));

            LineItemTax claimedLine = response.getLineItemTaxes().get(0);
            assertThat(claimedLine.isExemptionDenied()).isTrue();
            assertThat(claimedLine.getExemptionReasonCode()).isEqualTo(ExemptionReasonCode.RESALE);
            assertThat(claimedLine.getTaxAmount()).isEqualByComparingTo("4.40");
            LineItemTax exemptLine = response.getLineItemTaxes().get(1);
            assertThat(exemptLine.isTaxExempt()).isTrue();
            assertThat(exemptLine.getTaxAmount()).isEqualByComparingTo("0.00");
            assertThat(exemptLine.getJurisdictions()).singleElement().satisfies(j -> {
                assertThat(j.isExempt()).isTrue();
                assertThat(j.getTaxType()).isEqualTo("HST");
            });
            assertThat(response.getEffectiveTaxRate()).isEqualByComparingTo("4.40");
        }

        @Test
        @DisplayName("a committable document priced by a plug-in is recorded with the plug-in's id")
        void committablePricingIsRecordedWithThePlugin() {
            TaxCalculationRequest committable = request("CA", "BC", "2026-08-27", line("1", "10.00"));
            UUID referenceId = UUID.fromString("00000000-0000-7000-8000-0000000000bb");
            committable.setReferenceId(referenceId);
            committable.setReferenceType(TaxReferenceType.INVOICE);
            committable.setCommittable(true);

            firstCountry().calc().calculateTax(committable);

            verify(lifecycle).recordPricing(referenceId, "INVOICE", "CA_SELF");
        }

        @Test
        @DisplayName("a non-committable estimate records nothing")
        void estimateRecordsNothing() {
            firstCountry().calc().calculateTax(request("CA", "BC", "2026-08-27", line("1", "10.00")));

            verify(lifecycle, never()).recordPricing(any(), any(), eq("CA_SELF"));
        }
    }

    @Nested
    @DisplayName("a made-up country is configuration only (AC 2)")
    class MadeUpCountry {

        @Test
        @DisplayName("rates and calculation answer from the ZZ fixture with no code change")
        void zzAnswersFromConfiguration() {
            Services s = services(
                    false,
                    TaxProperties.Provider.AVALARA,
                    TaxProfileFixtures.FIRST_COUNTRY,
                    TaxProfileFixtures.MADE_UP_COUNTRY);

            TaxRateLookupResponse rates = s.rates().lookupRates("ZZ", "Z1", null, "00000", AS_OF);
            assertThat(rates.components())
                    .containsExactly(new TaxRateComponent(
                            TaxJurisdictionType.COUNTRY, new BigDecimal("0.07"), "ZZ_LEVY", false));
            assertThat(s.selector().selectFor("ZZ").providerName()).isEqualTo("ZZ_SELF");

            // The zero-decimal fixture currency rounds every row to whole units: 1234 x 0.07 = 86.38 -> 86.
            TaxCalculationResponse response =
                    s.calc().calculateTax(request("ZZ", "Z1", "2026-08-27", line("1", "1234")));
            assertThat(response.getTotalTax()).isEqualByComparingTo("86");
            assertThat(response.getTotalTax().scale()).isZero();
            assertThat(cell(response.getLineItemTaxes().get(0), "ZZ_LEVY").getCode())
                    .isEqualTo("ZZ");
            verifyNoInteractions(avalara);
        }
    }

    @Nested
    @DisplayName("every other country keeps today's switch (AC 3, AC 4)")
    class OtherCountries {

        @Test
        @DisplayName("a US address is still answered by the test-mode switch with untyped rows")
        void usKeepsTheSwitch() {
            Services s = firstCountry();

            assertThat(s.selector().selectFor("US")).isInstanceOf(TestModeTaxProvider.class);
            TaxRateLookupResponse rates = s.rates().lookupRates("US", "CA", null, "90001", AS_OF);
            assertThat(rates.source()).isEqualTo("TEST_MODE");
            assertThat(rates.components())
                    .containsExactly(
                            new TaxRateComponent(TaxJurisdictionType.STATE, new BigDecimal("0.0725"), null, null));

            TaxCalculationResponse response =
                    s.calc().calculateTax(request("US", "CA", "2026-08-27", line("1", "100.00")));
            assertThat(response.getLineItemTaxes().get(0).getJurisdictions()).allSatisfy(j -> {
                assertThat(j.getTaxType()).isNull();
                assertThat(j.getInputTaxRecoverable()).isNull();
            });
        }

        @Test
        @DisplayName("with test mode off, the US keeps the configured external adapter")
        void usKeepsTheExternalSwitch() {
            Services s = services(false, TaxProperties.Provider.AVALARA, TaxProfileFixtures.FIRST_COUNTRY);

            assertThat(s.selector().selectFor("US")).isSameAs(avalara);
            assertThat(s.selector().selectFor(null)).isSameAs(avalara);
            assertThat(s.selector().selectFor("CA").providerName()).isEqualTo("CA_SELF");
        }
    }

    @Nested
    @DisplayName("tax-types read (AC 5)")
    class TaxTypes {

        @Test
        @DisplayName("a profiled country returns its four types, regimes and currency")
        void profiledCountry() {
            TaxTypesResponse response = firstCountry().rates().lookupTaxTypes("CA");

            assertThat(response.currency()).isEqualTo("CAD");
            assertThat(response.source()).isEqualTo("STUB");
            assertThat(response.taxTypes())
                    .containsExactlyInAnyOrder(
                            new TaxTypesResponse.TaxTypeEntry("GST", "GST_HST", TaxJurisdictionType.COUNTRY, true),
                            new TaxTypesResponse.TaxTypeEntry("HST", "GST_HST", TaxJurisdictionType.PROVINCE, true),
                            new TaxTypesResponse.TaxTypeEntry("QST", "QST", TaxJurisdictionType.PROVINCE, true),
                            new TaxTypesResponse.TaxTypeEntry("PST", null, TaxJurisdictionType.PROVINCE, false));
            assertThat(response.regimes())
                    .containsExactlyInAnyOrder(
                            new TaxTypesResponse.RegimeEntry("GST_HST", List.of()),
                            new TaxTypesResponse.RegimeEntry("QST", List.of("QC")));
        }

        @Test
        @DisplayName("a country with no profile returns empty lists and a null currency")
        void countryWithoutProfile() {
            TaxTypesResponse response = firstCountry().rates().lookupTaxTypes("US");

            assertThat(response.countryCode()).isEqualTo("US");
            assertThat(response.currency()).isNull();
            assertThat(response.taxTypes()).isEmpty();
            assertThat(response.regimes()).isEmpty();
            assertThat(response.source()).isEqualTo("STUB");
        }
    }
}
