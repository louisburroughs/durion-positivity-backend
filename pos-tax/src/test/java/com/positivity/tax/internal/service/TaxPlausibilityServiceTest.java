package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.shared.error.ApiError;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.dto.PlausibilityCheckRequest;
import com.positivity.tax.internal.dto.PlausibilityCheckRequest.StatedTax;
import com.positivity.tax.internal.dto.PlausibilityCheckResponse;
import com.positivity.tax.internal.exception.TaxAmountImplausibleException;
import com.positivity.tax.internal.exception.TaxRequestInvalidException;
import com.positivity.tax.internal.exception.TaxRequestUnprocessableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * CAP:550 S32b ACs 3, 4 and 5: the stated-tax plausibility stub, against fixture rates (not tax law).
 * <p>
 * Fixture: region {@code ON} levies one {@code GST_HST} row at {@code r = 0.044} and no {@code QST} row;
 * region {@code AB} has no row at all. With {@code T = 113.00} the bound is
 * {@code ceil(113.00 × 0.044 / 1.044) = 4.77}, plus 5 minor units: {@code 4.82}.
 */
@DisplayName("TaxPlausibilityService (CAP:550 S32b)")
class TaxPlausibilityServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate AS_OF = LocalDate.parse("2026-08-27");
    private static final BigDecimal T = new BigDecimal("113.00");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @SafeVarargs
    private TaxPlausibilityService service(Consumer<Map<String, String>> change, Map<String, String>... base) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (Map<String, String> source : base) {
            properties.putAll(source);
        }
        change.accept(properties);
        TaxProperties bound = TaxProfileFixtures.bind(properties);
        TaxCountryProfiles profiles = new TaxCountryProfiles(bound);
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("meterRegistry", registry));
        return new TaxPlausibilityServiceImpl(
                bound,
                profiles,
                new RegistrationNumberShapes(bound, profiles),
                new TaxEvidenceRulesImpl(bound, profiles, CLOCK),
                CLOCK,
                beans.getBeanProvider(MeterRegistry.class));
    }

    private TaxPlausibilityService service() {
        return service(p -> {}, TaxProfileFixtures.FIRST_COUNTRY, TaxProfileFixtures.FIRST_COUNTRY_STUBS);
    }

    private static PlausibilityCheckRequest request(
            String region, BigDecimal total, List<StatedTax> stated, String number) {
        return new PlausibilityCheckRequest("CA", region, "A1A 1A1", null, AS_OF, "CAD", total, stated, number);
    }

    private static StatedTax tax(String regime, String amount) {
        return new StatedTax(regime, new BigDecimal(amount));
    }

    private static List<ApiError.FieldError> implausible(Runnable call) {
        try {
            call.run();
        } catch (TaxAmountImplausibleException ex) {
            return ex.getFieldErrors();
        }
        throw new AssertionError("expected TAX_AMOUNT_IMPLAUSIBLE");
    }

    @Nested
    @DisplayName("AC 4: the plausibility bound")
    class Bound {

        @Test
        @DisplayName("[M] an amount equal to ceil(T × r / (1 + r)) + 5 minor units is PLAUSIBLE")
        void amountAtTheBoundIsPlausible() {
            PlausibilityCheckResponse response =
                    service().check(request("ON", T, List.of(tax("GST_HST", "4.82")), null));

            assertThat(response.outcome()).isEqualTo("PLAUSIBLE");
            assertThat(response.maximums()).singleElement().satisfies(m -> {
                assertThat(m.regime()).isEqualTo("GST_HST");
                assertThat(m.maximum()).isEqualByComparingTo("4.82");
            });
            assertThat(response.ratesUsed()).singleElement().satisfies(r -> {
                assertThat(r.regime()).isEqualTo("GST_HST");
                assertThat(r.taxType()).isEqualTo("HST");
                assertThat(r.rate()).isEqualByComparingTo("0.044");
            });
            assertThat(response.source()).isEqualTo("STUB");
            assertThat(response.asOf()).isEqualTo(AS_OF);
        }

        @Test
        @DisplayName("one minor unit more is TAX_AMOUNT_IMPLAUSIBLE, naming the amount with its maximum")
        void oneMinorUnitMoreIsImplausible() {
            TaxPlausibilityService service = service();

            List<ApiError.FieldError> errors =
                    implausible(() -> service.check(request("ON", T, List.of(tax("GST_HST", "4.83")), null)));

            assertThat(errors).singleElement().satisfies(error -> {
                assertThat(error.field()).isEqualTo("statedTaxes[0].amount");
                assertThat(error.message()).contains("4.82");
            });
        }

        @Test
        @DisplayName("a regime the region's rows do not levy has r = 0: only the tolerance is plausible")
        void unleviedRegimeHasRateZero() {
            TaxPlausibilityService service = service();

            PlausibilityCheckResponse response = service.check(request("ON", T, List.of(tax("QST", "0.05")), null));
            assertThat(response.outcome()).isEqualTo("PLAUSIBLE");
            assertThat(response.ratesUsed()).isEmpty();
            assertThat(response.maximums())
                    .singleElement()
                    .satisfies(m -> assertThat(m.maximum()).isEqualByComparingTo("0.05"));

            assertThat(implausible(() -> service.check(request("ON", T, List.of(tax("QST", "0.06")), null))))
                    .singleElement()
                    .satisfies(error -> assertThat(error.message()).contains("0.05"));
        }

        @Test
        @DisplayName("each regime has its own bound; there is no combined bound")
        void noCombinedBound() {
            PlausibilityCheckResponse response =
                    service().check(request("ON", T, List.of(tax("GST_HST", "4.82"), tax("QST", "0.05")), null));

            assertThat(response.outcome()).isEqualTo("PLAUSIBLE");
            assertThat(response.maximums()).hasSize(2);
        }

        @Test
        @DisplayName("an amount at or above T with no rate is TAX_AMOUNT_IMPLAUSIBLE")
        void amountReachingTheTotalWithoutRate() {
            TaxPlausibilityService service = service();

            assertThat(implausible(() -> service.check(request("AB", T, List.of(tax("GST_HST", "113.00")), null))))
                    .singleElement()
                    .satisfies(error -> assertThat(error.field()).isEqualTo("statedTaxes[0].amount"));
        }

        @Test
        @DisplayName("an amount at or above T with a rate names its maximum, not only the total")
        void amountReachingTheTotalWithRateCarriesItsMaximum() {
            TaxPlausibilityService service = service();

            assertThat(implausible(() -> service.check(request("ON", T, List.of(tax("GST_HST", "113.00")), null))))
                    .singleElement()
                    .satisfies(error -> {
                        assertThat(error.field()).isEqualTo("statedTaxes[0].amount");
                        assertThat(error.message()).contains("4.82");
                    });
        }

        @Test
        @DisplayName("stated amounts whose sum reaches T are TAX_AMOUNT_IMPLAUSIBLE")
        void sumReachingTheTotal() {
            TaxPlausibilityService service = service();

            assertThat(implausible(() -> service.check(request(
                            "AB", new BigDecimal("1.00"), List.of(tax("GST_HST", "0.60"), tax("QST", "0.40")), null))))
                    .singleElement()
                    .satisfies(error -> assertThat(error.field()).isEqualTo("statedTaxes"));
        }

        @Test
        @DisplayName("a plausible amount with no rate answers RATE_UNAVAILABLE, with no maximum")
        void plausibleAmountWithoutRate() {
            PlausibilityCheckResponse response =
                    service().check(request("AB", T, List.of(tax("GST_HST", "10.00")), null));

            assertThat(response.outcome()).isEqualTo("RATE_UNAVAILABLE");
            assertThat(response.maximums()).isEmpty();
            assertThat(response.ratesUsed()).isEmpty();
        }

        @Test
        @DisplayName("zero or absent amounts are PLAUSIBLE")
        void zeroOrAbsentAmounts() {
            TaxPlausibilityService service = service();

            assertThat(service.check(request("ON", T, null, null)).outcome()).isEqualTo("PLAUSIBLE");
            assertThat(service.check(request("ON", T, List.of(), null)).outcome())
                    .isEqualTo("PLAUSIBLE");
            assertThat(service.check(request("ON", T, List.of(tax("GST_HST", "0")), null))
                            .outcome())
                    .isEqualTo("PLAUSIBLE");
        }

        @Test
        @DisplayName("the counter is tagged by outcome only")
        void counterTaggedByOutcome() {
            TaxPlausibilityService service = service();
            service.check(request("ON", T, List.of(), "000000000RT0001"));
            service.check(request("AB", T, List.of(), null));
            implausible(() -> service.check(request("ON", T, List.of(tax("GST_HST", "5.00")), null)));

            assertThat(registry.get("pos.tax.plausibility.outcome")
                            .tag("outcome", "PLAUSIBLE")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(registry.get("pos.tax.plausibility.outcome")
                            .tag("outcome", "RATE_UNAVAILABLE")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(registry.get("pos.tax.plausibility.outcome")
                            .tag("outcome", "TAX_AMOUNT_IMPLAUSIBLE")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(registry.get("pos.tax.plausibility.outcome").counters())
                    .allSatisfy(counter -> assertThat(counter.getId().getTags())
                            .extracting(tag -> tag.getKey())
                            .containsExactly("outcome"));
        }
    }

    @Nested
    @DisplayName("Refusals before the bound: 400 for shape, 422 for configuration (ADR-0017, ADR-0067)")
    class Refused {

        private TaxRequestUnprocessableException unprocessable(PlausibilityCheckRequest request) {
            TaxPlausibilityService service = service();
            try {
                service.check(request);
            } catch (TaxRequestUnprocessableException ex) {
                return ex;
            }
            throw new AssertionError("expected a 422 refusal");
        }

        @Test
        @DisplayName("[M] a currency other than the profile's is 422 CURRENCY_NOT_SUPPORTED")
        void currencyOtherThanTheProfiles() {
            PlausibilityCheckRequest request =
                    new PlausibilityCheckRequest("CA", "ON", "A1A 1A1", null, AS_OF, "USD", T, List.of(), null);

            TaxRequestUnprocessableException ex = unprocessable(request);

            assertThat(ex.getCode()).isEqualTo("CURRENCY_NOT_SUPPORTED");
            assertThat(ex.getFieldErrors())
                    .extracting(ApiError.FieldError::field)
                    .containsExactly("currencyCode");
        }

        @Test
        @DisplayName("a regime not declared for the country is 422 TAX_REGIME_NOT_DECLARED")
        void regimeNotDeclaredForTheCountry() {
            TaxRequestUnprocessableException ex =
                    unprocessable(request("ON", T, List.of(tax("NO_SUCH", "1.00")), null));

            assertThat(ex.getCode()).isEqualTo("TAX_REGIME_NOT_DECLARED");
            assertThat(ex.getFieldErrors())
                    .extracting(ApiError.FieldError::field)
                    .containsExactly("statedTaxes[0].regime");
        }

        @Test
        @DisplayName("[M] amounts finer than the currency are 422 AMOUNT_PRECISION_EXCEEDS_CURRENCY, each named")
        void amountsFinerThanTheCurrency() {
            TaxRequestUnprocessableException ex =
                    unprocessable(request("ON", new BigDecimal("113.001"), List.of(tax("GST_HST", "1.001")), null));

            assertThat(ex.getCode()).isEqualTo("AMOUNT_PRECISION_EXCEEDS_CURRENCY");
            assertThat(ex.getFieldErrors())
                    .extracting(ApiError.FieldError::field)
                    .containsExactly("receiptTotal", "statedTaxes[0].amount");
        }

        @Test
        @DisplayName("trailing zeros are not extra precision")
        void trailingZerosAreNotPrecision() {
            assertThat(service()
                            .check(request("ON", new BigDecimal("113.0000"), List.of(), null))
                            .outcome())
                    .isEqualTo("PLAUSIBLE");
        }

        @Test
        @DisplayName("a country without a profile is 422 TAX_JURISDICTION_NOT_CONFIGURED")
        void countryWithoutAProfile() {
            PlausibilityCheckRequest request =
                    new PlausibilityCheckRequest("US", "NY", "10001", null, AS_OF, "USD", T, List.of(), null);

            TaxRequestUnprocessableException ex = unprocessable(request);

            assertThat(ex.getCode()).isEqualTo("TAX_JURISDICTION_NOT_CONFIGURED");
            assertThat(ex.getFieldErrors())
                    .extracting(ApiError.FieldError::field)
                    .containsExactly("countryCode");
        }

        @Test
        @DisplayName("a repeated regime is request shape: 400, before the configuration is read")
        void duplicatedRegime() {
            TaxPlausibilityService service = service();
            PlausibilityCheckRequest request = new PlausibilityCheckRequest(
                    "US", "NY", "10001", null, AS_OF, "USD", T, List.of(tax("X_1", "1"), tax("X_1", "1")), null);

            assertThatThrownBy(() -> service.check(request))
                    .isInstanceOfSatisfying(
                            TaxRequestInvalidException.class,
                            ex -> assertThat(ex.getFieldErrors())
                                    .extracting(ApiError.FieldError::field)
                                    .containsExactly("statedTaxes[1].regime"));
        }
    }

    @Nested
    @DisplayName("AC 3: the evidence rule and the supplier's number")
    class Supplier {

        @Test
        @DisplayName("150.00 without a number: required, well-formed null")
        void requiredWithoutNumber() {
            PlausibilityCheckResponse response =
                    service().check(request("ON", new BigDecimal("150.00"), List.of(), null));

            assertThat(response.supplierRegistrationRequired()).isTrue();
            assertThat(response.supplierRegistrationNumberWellFormed()).isNull();
        }

        @Test
        @DisplayName("80.00: not required")
        void notRequiredBelowTheThreshold() {
            assertThat(service()
                            .check(request("ON", new BigDecimal("80.00"), List.of(), null))
                            .supplierRegistrationRequired())
                    .isFalse();
        }

        @Test
        @DisplayName("a number is checked against the country's supplier regime shape, without changing outcome")
        void numberIsShapeChecked() {
            TaxPlausibilityService service = service();

            PlausibilityCheckResponse good = service.check(request("ON", T, List.of(), "000 000 000 RT 0001"));
            PlausibilityCheckResponse bad = service.check(request("ON", T, List.of(), "000000000"));

            assertThat(good.supplierRegistrationNumberWellFormed()).isTrue();
            assertThat(bad.supplierRegistrationNumberWellFormed()).isFalse();
            assertThat(bad.outcome()).isEqualTo("PLAUSIBLE");
        }

        @Test
        @DisplayName("a country that names no supplier regime answers null for a number")
        void noSupplierRegime() {
            TaxPlausibilityService service = service(
                    p -> p.remove("pos.tax.countries.CA.supplier-registration-regime"),
                    TaxProfileFixtures.FIRST_COUNTRY,
                    TaxProfileFixtures.FIRST_COUNTRY_STUBS);

            assertThat(service.check(request("ON", T, List.of(), "000000000RT0001"))
                            .supplierRegistrationNumberWellFormed())
                    .isNull();
        }
    }

    @Test
    @DisplayName("AC 5: a made-up country answers from configuration alone, at its own currency exponent")
    void madeUpCountry() {
        TaxPlausibilityService service =
                service(p -> {}, TaxProfileFixtures.MADE_UP_COUNTRY, TaxProfileFixtures.MADE_UP_COUNTRY_STUBS);
        BigDecimal total = new BigDecimal("1070");

        // ceil(1070 × 0.07 / 1.07) = 70, plus 5 minor units of a zero-decimal currency.
        PlausibilityCheckResponse response = service.check(new PlausibilityCheckRequest(
                "ZZ", "Z1", "00000", null, AS_OF, "JPY", total, List.of(tax("R_1", "75")), "zz-12345"));

        assertThat(response.outcome()).isEqualTo("PLAUSIBLE");
        assertThat(response.maximums())
                .singleElement()
                .satisfies(m -> assertThat(m.maximum()).isEqualByComparingTo("75"));
        assertThat(response.supplierRegistrationRequired()).isTrue();
        assertThat(response.supplierRegistrationNumberWellFormed()).isTrue();
        assertThat(implausible(() -> service.check(new PlausibilityCheckRequest(
                        "ZZ", "Z1", "00000", null, AS_OF, "JPY", total, List.of(tax("R_1", "76")), null))))
                .singleElement()
                .satisfies(error -> assertThat(error.message()).contains("75"));
    }

    @Test
    @DisplayName("a missing or negative tolerance fails startup, naming the property")
    void toleranceIsRequired() {
        assertThatThrownBy(() -> service(
                        p -> p.remove("pos.tax.plausibility.tolerance-minor-units"),
                        TaxProfileFixtures.FIRST_COUNTRY,
                        TaxProfileFixtures.FIRST_COUNTRY_STUBS))
                .hasMessageStartingWith("Invalid tax configuration pos.tax.plausibility.tolerance-minor-units:");
        assertThatThrownBy(() -> service(
                        p -> p.put("pos.tax.plausibility.tolerance-minor-units", "-1"),
                        TaxProfileFixtures.FIRST_COUNTRY,
                        TaxProfileFixtures.FIRST_COUNTRY_STUBS))
                .hasMessageStartingWith("Invalid tax configuration pos.tax.plausibility.tolerance-minor-units:");
    }
}
