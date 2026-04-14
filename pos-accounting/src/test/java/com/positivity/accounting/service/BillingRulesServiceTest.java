package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.CustomerBillingRulesClient;
import com.positivity.accounting.internal.client.CustomerServiceException;
import com.positivity.accounting.internal.dto.BillingRuleRefResponse;
import com.positivity.accounting.internal.service.BillingRulesServiceImpl;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for BillingRulesServiceImpl.
 *
 * Tests billing rules retrieval with remote client success and error scenarios.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingRulesService Unit Tests")
class BillingRulesServiceTest {

    @Mock
    private CustomerBillingRulesClient customerBillingRulesClient;

    @InjectMocks
    private BillingRulesServiceImpl service;

    @Test
    @DisplayName("getBillingRules - returns response when client succeeds")
    void getBillingRules_success() {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BillingRuleRefResponse expected = new BillingRuleRefResponse();
        expected.setPaymentTerms("NET30");
        expected.setCreditLimit(new BigDecimal("5000.00"));

        when(customerBillingRulesClient.getBillingRules(customerId)).thenReturn(expected);

        BillingRuleRefResponse result = service.getBillingRules(customerId);

        assertThat(result).isNotNull();
        assertThat(result.getPaymentTerms()).isEqualTo("NET30");
    }

    @Test
    @DisplayName("getBillingRules - throws ResponseStatusException with mapped status on CustomerServiceException")
    void getBillingRules_clientError_mapsStatus() {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(customerBillingRulesClient.getBillingRules(customerId))
                .thenThrow(new CustomerServiceException("Not found", 404));

        assertThatThrownBy(() -> service.getBillingRules(customerId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("getBillingRules - uses SERVICE_UNAVAILABLE for unresolvable HTTP status code")
    void getBillingRules_unknownStatus_usesServiceUnavailable() {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // Use an invalid/unresolvable status code (negative)
        when(customerBillingRulesClient.getBillingRules(customerId))
                .thenThrow(new CustomerServiceException("Unknown error", -1));

        assertThatThrownBy(() -> service.getBillingRules(customerId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }
}
