package com.positivity.tax.service;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.dto.TaxCalculationRequest;
import com.positivity.tax.internal.dto.TaxCalculationResponse;
import com.positivity.tax.internal.dto.TaxLineItem;
import com.positivity.tax.internal.service.ExternalTaxServiceClient;
import com.positivity.tax.internal.service.TaxCalculationServiceImpl;
import com.positivity.tax.internal.service.TestModeTaxCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaxCalculationService Tests")
class TaxCalculationServiceTest {

    private static final String TEST_POSTAL_CODE = "12345";
    private static final String SUBTOTAL_150 = "150.00";
    private static final String TAX_RATE_10 = "10.00";

    @Mock
    private ExternalTaxServiceClient externalClient;

    private TaxProperties properties;
    private TaxCalculationServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new TaxProperties();
        
        // Configure test mode with default rates
        TaxProperties.TestMode testMode = new TaxProperties.TestMode();
        testMode.setEnabled(true);
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("STATE", new BigDecimal("0.08"));
        rates.put("COUNTY", new BigDecimal("0.01"));
        rates.put("CITY", new BigDecimal("0.01"));
        testMode.setDefaultRates(rates);
        properties.setTestMode(testMode);
        
        TestModeTaxCalculator testCalculator = new TestModeTaxCalculator(properties);
        service = new TaxCalculationServiceImpl(properties, testCalculator, externalClient);
    }

    @Test
    @DisplayName("Should calculate tax in test mode")
    void shouldCalculateTaxInTestMode() {
        // Given
        TaxCalculationRequest request = createSampleRequest();
        
        // When
        TaxCalculationResponse response = service.calculateTax(request);
        
        // Then
        assertThat(response).isNotNull();
        assertThat(response.isTestMode()).isTrue();
        assertThat(response.getSubtotal()).isEqualByComparingTo(SUBTOTAL_150);
        assertThat(response.getTotalTax()).isEqualByComparingTo("15.00"); // 10% combined rate
        assertThat(response.getTotal()).isEqualByComparingTo("165.00");
        assertThat(response.getEffectiveTaxRate()).isEqualByComparingTo(TAX_RATE_10);
        assertThat(response.getJurisdictions()).hasSize(3); // STATE, COUNTY, CITY
        
        // Verify external service was not called
        verifyNoInteractions(externalClient);
    }

    @Test
    @DisplayName("Should route to external service when test mode disabled")
    void shouldRouteToExternalServiceWhenTestModeDisabled() {
        // Given
        properties.getTestMode().setEnabled(false);
        TaxCalculationRequest request = createSampleRequest();
        
        TaxCalculationResponse mockResponse = TaxCalculationResponse.builder()
            .subtotal(new BigDecimal(SUBTOTAL_150))
            .totalTax(new BigDecimal("12.00"))
            .total(new BigDecimal("162.00"))
            .testMode(false)
            .build();
        
        when(externalClient.calculateTax(any())).thenReturn(mockResponse);
        
        // When
        TaxCalculationResponse response = service.calculateTax(request);
        
        // Then
        assertThat(response).isNotNull();
        assertThat(response.isTestMode()).isFalse();
        verify(externalClient, times(1)).calculateTax(request);
    }

    @Test
    @DisplayName("Should validate request and reject empty line items")
    void shouldValidateRequestAndRejectEmptyLineItems() {
        // Given
        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .lineItems(List.of())
            .postalCode(TEST_POSTAL_CODE)
            .build();
        
        // When & Then
        assertThatThrownBy(() -> service.calculateTax(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("At least one line item is required");
    }

    @Test
    @DisplayName("Should validate request and reject missing postal code")
    void shouldValidateRequestAndRejectMissingPostalCode() {
        // Given
        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .lineItems(List.of(createLineItem("1", "Item", "1", "100")))
            .postalCode("")
            .build();
        
        // When & Then
        assertThatThrownBy(() -> service.calculateTax(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Postal code is required");
    }

    @Test
    @DisplayName("Should validate line items have required fields")
    void shouldValidateLineItemsHaveRequiredFields() {
        // Given
        TaxLineItem invalidItem = TaxLineItem.builder()
            .lineItemId("")  // Invalid: blank ID
            .description("Item")
            .quantity(new BigDecimal("1"))
            .unitPrice(new BigDecimal("100"))
            .build();
        
        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .lineItems(List.of(invalidItem))
            .postalCode(TEST_POSTAL_CODE)
            .build();
        
        // When & Then
        assertThatThrownBy(() -> service.calculateTax(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lineItemId");
    }

    @Test
    @DisplayName("Should handle tax-exempt items correctly")
    void shouldHandleTaxExemptItemsCorrectly() {
        // Given
        TaxLineItem taxableItem = createLineItem("1", "Taxable Item", "1", "100");
        TaxLineItem exemptItem = createLineItem("2", "Exempt Item", "1", "50");
        exemptItem.setTaxExempt(true);
        
        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .lineItems(List.of(taxableItem, exemptItem))
            .postalCode(TEST_POSTAL_CODE)
            .stateCode("CA")
            .city("Los Angeles")
            .build();
        
        // When
        TaxCalculationResponse response = service.calculateTax(request);
        
        // Then
        assertThat(response).isNotNull();
        assertThat(response.getSubtotal()).isEqualByComparingTo(SUBTOTAL_150);
        // Tax should only apply to taxable item ($100 * 10% = $10)
        assertThat(response.getTotalTax()).isEqualByComparingTo(TAX_RATE_10);
        
        // Check line item breakdown
        assertThat(response.getLineItemTaxes())
            .hasSize(2)
            .anySatisfy(lit -> {
                assertThat(lit.getLineItemId()).isEqualTo("1");
                assertThat(lit.getTaxAmount()).isEqualByComparingTo(TAX_RATE_10);
            })
            .anySatisfy(lit -> {
                assertThat(lit.getLineItemId()).isEqualTo("2");
                assertThat(lit.isTaxExempt()).isTrue();
                assertThat(lit.getTaxAmount()).isEqualByComparingTo("0.00");
            });
    }

    @Test
    @DisplayName("Should report test mode status correctly")
    void shouldReportTestModeStatusCorrectly() {
        // When test mode enabled
        assertThat(service.isTestMode()).isTrue();
        
        // When test mode disabled
        properties.getTestMode().setEnabled(false);
        assertThat(service.isTestMode()).isFalse();
    }

    // Helper methods

    private TaxCalculationRequest createSampleRequest() {
        return TaxCalculationRequest.builder()
            .lineItems(List.of(
                createLineItem("1", "Part A", "2", "50"),
                createLineItem("2", "Part B", "1", "50")
            ))
            .postalCode(TEST_POSTAL_CODE)
            .stateCode("CA")
            .city("Los Angeles")
            .countryCode("US")
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
