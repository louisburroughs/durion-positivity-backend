package com.positivity.tax.common.validation;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import static org.assertj.core.api.Assertions.assertThat;

class TaxAddressValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("should accept valid ISO country and subdivision combination")
    void shouldAcceptValidCountryAndSubdivision() {
        TaxCalculationRequest.TaxAddress address = TaxCalculationRequest.TaxAddress.builder()
                .countryCode("US")
                .regionCode("CA")
                .postalCode("90001")
                .build();

        assertThat(validator.validate(address)).isEmpty();
    }

    @Test
    @DisplayName("should reject invalid subdivision for country")
    void shouldRejectInvalidSubdivisionForCountry() {
        TaxCalculationRequest.TaxAddress address = TaxCalculationRequest.TaxAddress.builder()
                .countryCode("US")
                .regionCode("ZZ")
                .postalCode("90001")
                .build();

        assertThat(validator.validate(address))
                .extracting(v -> v.getMessage())
                .contains("regionCode must be a valid subdivision for countryCode");
    }

    @Test
    @DisplayName("should accept full ISO 3166-2 form with country prefix")
    void shouldAcceptRegionWithCountryPrefix() {
        TaxCalculationRequest.TaxAddress address = TaxCalculationRequest.TaxAddress.builder()
                .countryCode("US")
                .regionCode("US-CA")
                .postalCode("90001")
                .build();

        assertThat(validator.validate(address)).isEmpty();
    }
}
