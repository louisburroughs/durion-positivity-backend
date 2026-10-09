package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.enums.TaxCalculationType;
import com.positivity.tax.internal.exception.TaxCalculationTypeUnsupportedException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CAP:550 S43, AC 13: the generic external provider answers 501 for {@code USE}, never calling out. */
@DisplayName("ExternalTaxProvider — USE is unsupported (S43)")
class ExternalTaxProviderUseTest {

    @Test
    @DisplayName("USE throws TaxCalculationTypeUnsupportedException before the HTTP client is called")
    void useIsUnsupported() {
        ExternalTaxServiceClient client = mock(ExternalTaxServiceClient.class);
        ExternalTaxProvider provider = new ExternalTaxProvider(client);
        TaxCalculationRequest use = TaxCalculationRequest.builder()
                .lineItems(List.of(TaxLineItem.builder()
                        .lineItemId("1")
                        .description("Shop supplies")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("200.00"))
                        .build()))
                .destinationAddress(TaxCalculationRequest.TaxAddress.builder()
                        .countryCode("ZZ")
                        .postalCode("00000")
                        .build())
                .calculationType(TaxCalculationType.USE)
                .build();

        assertThatThrownBy(() -> provider.estimate(use))
                .isInstanceOf(TaxCalculationTypeUnsupportedException.class)
                .hasMessageContaining("EXTERNAL");
        verifyNoInteractions(client);
    }
}
