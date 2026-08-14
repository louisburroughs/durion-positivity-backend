package com.positivity.tax.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * First tests for pos-tax-common, whose own suite was empty — its aggregate
 * coverage reading came entirely from consumers' tests, so nothing pinned these
 * validators directly.
 *
 * <p>
 * All three validators treat absence as valid on purpose: presence is
 * {@code @NotNull}'s job on the request, and a validator that also rejected null
 * would make every optional address field mandatory everywhere the annotation is
 * used. What they reject is a <em>present but wrong</em> value — the case that
 * would otherwise reach a tax provider and come back as a billing-time error.
 *
 * <p>
 * The subdivision check normalizes {@code US-TX} and {@code tx} to {@code TX}
 * before matching, and carries explicit US and CA fallback lists because the ISO
 * dataset alone does not cover the US territories tax law cares about (PR, GU,
 * DC, ...) or any Canadian province at all.
 */
@DisplayName("pos-tax-common validators — country, currency, subdivision")
class TaxValidationTest {

    @Nested
    @DisplayName("IsoCountryCodeValidator")
    class Country {

        private final IsoCountryCodeValidator validator = new IsoCountryCodeValidator();

        @ParameterizedTest
        @ValueSource(strings = {"US", "CA", "DE", "JP"})
        @DisplayName("accepts real ISO 3166-1 alpha-2 codes")
        void acceptsRealCodes(String code) {
            assertThat(validator.isValid(code, null)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"USA", "XX", "us", "U1"})
        @DisplayName("rejects a present but wrong code, including lowercase")
        void rejectsWrongCodes(String code) {
            // Lowercase is rejected on purpose: the DTO contract is uppercase alpha-2, and
            // accepting "us" here would let mixed-case reach providers that compare exactly.
            assertThat(validator.isValid(code, null)).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        @DisplayName("treats absence as valid, leaving presence to @NotNull")
        void absentIsValid(String code) {
            assertThat(validator.isValid(code, null)).isTrue();
        }
    }

    @Nested
    @DisplayName("IsoCurrencyCodeValidator")
    class Currency {

        private final IsoCurrencyCodeValidator validator = new IsoCurrencyCodeValidator();

        @ParameterizedTest
        @CsvSource({"USD, true", "EUR, true", "CAD, true", "XYZ, false", "DOLLARS, false"})
        @DisplayName("accepts ISO 4217 codes and rejects invented ones")
        void currencyCodes(String code, boolean valid) {
            assertThat(validator.isValid(code, null)).isEqualTo(valid);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("treats absence as valid")
        void absentIsValid(String code) {
            assertThat(validator.isValid(code, null)).isTrue();
        }
    }

    @Nested
    @DisplayName("SubdivisionForCountryValidator")
    class Subdivision {

        private final SubdivisionForCountryValidator validator = new SubdivisionForCountryValidator();

        private static TaxCalculationRequest.TaxAddress address(String country, String region) {
            return TaxCalculationRequest.TaxAddress.builder()
                    .countryCode(country)
                    .regionCode(region)
                    .build();
        }

        @ParameterizedTest
        @CsvSource({"US, TX, true", "US, US-TX, true", "US, tx, true", "US, ZZ, false", "CA, TX, false", "DE, BY, true"
        })
        @DisplayName("accepts a subdivision only within its own country, normalizing prefix and case")
        void subdivisionMembership(String country, String region, boolean valid) {
            // TX is a real code — but not a Canadian one. Cross-country acceptance would send a
            // Canadian address to the provider with a Texas jurisdiction.
            assertThat(validator.isValid(address(country, region), null)).isEqualTo(valid);
        }

        @ParameterizedTest
        @CsvSource({
            "CA, ON", "CA, QC", "CA, BC", "CA, AB", "CA, MB", "CA, NB", "CA, NL", "CA, NS", "CA, NT", "CA, NU",
            "CA, PE", "CA, SK", "CA, YT"
        })
        @DisplayName("accepts every real Canadian province and territory via the explicit fallback list")
        void canadianProvincesAndTerritoriesAreAccepted(String country, String region) {
            // The i18n-subdivision-enums dataset returns no subdivisions for Canada (probing
            // yields only "NA"), so — like the US territories below — CA needs an explicit
            // fallback list rather than relying on the backing dataset.
            assertThat(validator.isValid(address(country, region), null)).isTrue();
        }

        @ParameterizedTest
        @CsvSource({"US, PR", "US, GU", "US, DC", "US, VI"})
        @DisplayName("accepts US territories via the explicit fallback list")
        void usTerritories(String country, String region) {
            // Tax law reaches the territories even where the ISO dataset is thin.
            assertThat(validator.isValid(address(country, region), null)).isTrue();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("treats a missing address, country, or region as valid")
        void absentPartsAreValid() {
            assertThat(validator.isValid(null, null)).isTrue();
            assertThat(validator.isValid(address(null, "TX"), null)).isTrue();
            assertThat(validator.isValid(address("US", "  "), null)).isTrue();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("rejects an unknown country outright, even with a plausible region")
        void unknownCountryIsInvalid() {
            assertThat(validator.isValid(address("XX", "TX"), null)).isFalse();
        }
    }
}
