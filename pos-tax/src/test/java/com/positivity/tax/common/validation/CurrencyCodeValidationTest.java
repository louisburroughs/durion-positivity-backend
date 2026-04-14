package com.positivity.tax.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CurrencyCodeValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("should accept valid ISO 4217 currency code")
    void shouldAcceptValidCurrencyCode() {
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of())
                .destinationAddress(TaxCalculationRequest.TaxAddress.builder()
                        .countryCode("US")
                        .postalCode("90001")
                        .build())
                .currencyCode("USD")
                .build();

        assertThat(validator.validateProperty(request, "currencyCode")).isEmpty();
    }

    @Test
    @DisplayName("should reject unknown currency code")
    void shouldRejectUnknownCurrencyCode() {
        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(List.of())
                .destinationAddress(TaxCalculationRequest.TaxAddress.builder()
                        .countryCode("US")
                        .postalCode("90001")
                        .build())
                .currencyCode("ZZZ")
                .build();

        assertThat(validator.validateProperty(request, "currencyCode"))
                .extracting(v -> v.getMessage())
                .contains("currencyCode must be a valid ISO 4217 code");
    }
}
