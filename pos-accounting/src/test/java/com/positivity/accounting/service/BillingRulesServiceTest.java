package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.BillingRuleRefResponse;
import com.positivity.accounting.internal.entity.ExtCustomerBillingRules;
import com.positivity.accounting.internal.repository.ExtCustomerBillingRulesRepository;
import com.positivity.accounting.internal.service.BillingRulesServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for BillingRulesServiceImpl reading the ext_customer_billing_rules replica
 * (ADR-0044 R3, issue #842) — the synchronous pos-customer client is retired.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingRulesService Unit Tests")
class BillingRulesServiceTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private ExtCustomerBillingRulesRepository repository;

    @InjectMocks
    private BillingRulesServiceImpl service;

    @Test
    @DisplayName("getBillingRules - maps replica row onto owner defaults")
    void getBillingRules_fromReplica() {
        when(repository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(ExtCustomerBillingRules.builder()
                        .partyId(CUSTOMER_ID)
                        .poRequired(true)
                        .paymentTerms("NET30")
                        .creditLimit(new BigDecimal("5000.00"))
                        .aggregateVersion(42L)
                        .updatedAt(Instant.parse("2026-07-08T12:00:00Z"))
                        .build()));

        BillingRuleRefResponse result = service.getBillingRules(CUSTOMER_ID);

        assertThat(result.isPoRequired()).isTrue();
        assertThat(result.getPaymentTerms()).isEqualTo("NET30");
        assertThat(result.getCreditLimit()).isEqualByComparingTo("5000.00");
        // Unset replica fields fall back to owner defaults.
        assertThat(result.isTaxExempt()).isFalse();
        assertThat(result.getInvoiceDeliveryMethod()).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("getBillingRules - replica miss returns the owner's documented defaults")
    void getBillingRules_missReturnsDefaults() {
        when(repository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        BillingRuleRefResponse result = service.getBillingRules(CUSTOMER_ID);

        assertThat(result.isPoRequired()).isFalse();
        assertThat(result.isCreditHold()).isFalse();
        assertThat(result.getPaymentTerms()).isEqualTo("Due on Receipt");
        assertThat(result.getInvoiceDeliveryMethod()).isEqualTo("EMAIL");
        assertThat(result.getCreditLimit()).isNull();
    }
}
