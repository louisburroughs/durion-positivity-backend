package com.positivity.domainevents.customer;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a commercial party's billing rules changed (ADR-0044, issue #842 Phase 1).
 *
 * <p>Published by pos-customer on {@code customer.events.v1} with
 * {@code eventType = "customer.billing-rules.updated"}; consumed by pos-accounting into its
 * read-only {@code ext_customer_billing_rules} replica. Fields mirror the owner's
 * {@code BillingRulesEmbeddable}; additions must be additive-only within schema version 1.
 *
 * @param partyId owning commercial party (also the envelope aggregateId)
 */
public record BillingRulesUpdatedV1(
        @NonNull UUID partyId,
        @Nullable Boolean poRequired,
        @Nullable Boolean taxExempt,
        @Nullable Boolean creditHold,
        @Nullable Boolean autoPayEnabled,
        @Nullable String paymentTerms,
        @Nullable BigDecimal creditLimit,
        @Nullable String currency,
        @Nullable String invoiceDeliveryMethod,
        @Nullable UUID billingAddressId,
        @Nullable String discountPolicyRef) {

    public static final String EVENT_TYPE = "customer.billing-rules.updated";

    public BillingRulesUpdatedV1 {
        if (partyId == null) {
            throw new IllegalArgumentException("partyId must not be null");
        }
    }
}
