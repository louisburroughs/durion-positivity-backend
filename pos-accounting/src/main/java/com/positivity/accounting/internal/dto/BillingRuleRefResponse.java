package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

/**
 * Billing rules snapshot returned by pos-customer.
 */
@Data
public class BillingRuleRefResponse {

    private boolean poRequired;
    private boolean taxExempt;
    private String paymentTerms;
    private BigDecimal creditLimit;
    private boolean creditHold;
    private String invoiceDeliveryMethod;
    private UUID billingAddressId;
    private boolean autoPayEnabled;
    private String discountPolicyRef;
    private String currency;
    private Map<String, Object> extensions;
}
