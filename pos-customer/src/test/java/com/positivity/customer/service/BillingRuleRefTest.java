package com.positivity.customer.service;

import com.positivity.customer.internal.dto.snapshot.BillingRuleRef;
import com.positivity.customer.internal.enums.InvoiceDeliveryMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BillingRuleRefTest {

    @Test
    void defaults_returnsAllRequiredFields() {
        BillingRuleRef ref = BillingRuleRef.defaults();

        assertThat(ref.isPoRequired()).isFalse();
        assertThat(ref.isTaxExempt()).isFalse();
        assertThat(ref.getPaymentTerms()).isEqualTo("Due on Receipt");
        assertThat(ref.getCreditLimit()).isNull();
        assertThat(ref.isCreditHold()).isFalse();
        assertThat(ref.getInvoiceDeliveryMethod()).isEqualTo(InvoiceDeliveryMethod.EMAIL);
        assertThat(ref.isAutoPayEnabled()).isFalse();
    }

    @Test
    void settersAndGetters_workCorrectly() {
        BillingRuleRef ref = new BillingRuleRef();
        UUID addressId = UUID.randomUUID();

        ref.setPoRequired(true);
        ref.setTaxExempt(true);
        ref.setPaymentTerms("Net 30");
        ref.setCreditLimit(new BigDecimal("5000.00"));
        ref.setCreditHold(true);
        ref.setInvoiceDeliveryMethod(InvoiceDeliveryMethod.PORTAL);
        ref.setBillingAddressId(addressId);
        ref.setAutoPayEnabled(true);
        ref.setDiscountPolicyRef("DISC-10");
        ref.setCurrency("USD");
        ref.setExtensions(Map.of("level", "gold"));

        assertThat(ref.isPoRequired()).isTrue();
        assertThat(ref.isTaxExempt()).isTrue();
        assertThat(ref.getPaymentTerms()).isEqualTo("Net 30");
        assertThat(ref.getCreditLimit()).isEqualByComparingTo("5000.00");
        assertThat(ref.isCreditHold()).isTrue();
        assertThat(ref.getInvoiceDeliveryMethod()).isEqualTo(InvoiceDeliveryMethod.PORTAL);
        assertThat(ref.getBillingAddressId()).isEqualTo(addressId);
        assertThat(ref.isAutoPayEnabled()).isTrue();
        assertThat(ref.getDiscountPolicyRef()).isEqualTo("DISC-10");
        assertThat(ref.getCurrency()).isEqualTo("USD");
        assertThat(ref.getExtensions()).containsEntry("level", "gold");
    }
}
