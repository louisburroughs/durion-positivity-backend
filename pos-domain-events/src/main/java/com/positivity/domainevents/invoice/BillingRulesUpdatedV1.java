package com.positivity.domainevents.invoice;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a commercial account's billing rules were created or changed (ADR-0044 §6, issue #902
 * Phase 5.6).
 *
 * <p>Published by pos-invoice on {@code invoice.events.v1} with
 * {@code eventType = "invoice.billing-rules.updated"} — one snapshot per billing-rules row
 * touched in a business transaction; the envelope's {@code aggregateVersion} is the row's JPA
 * optimistic-lock version. Consumers (pos-workorder) keep an {@code ext_billing_rules} replica
 * serving purchase-order enforcement (CAP:092 #98), replacing the retired synchronous
 * billing-rules read.
 *
 * @param partyId commercial account party identifier (owner key of the rules row)
 * @param purchaseOrderRequired whether estimate/workorder approval requires a purchase order
 * @param paymentTermsCode payment terms code (e.g. NET_30)
 * @param invoiceDeliveryMethod delivery method name (EMAIL, PRINT, ...)
 * @param invoiceGroupingStrategy grouping strategy name (PER_WORKORDER, ...)
 */
public record BillingRulesUpdatedV1(
        @NonNull String partyId,
        boolean purchaseOrderRequired,
        @Nullable String paymentTermsCode,
        @Nullable String invoiceDeliveryMethod,
        @Nullable String invoiceGroupingStrategy) {

    public static final String EVENT_TYPE = "invoice.billing-rules.updated";
    public static final int SCHEMA_VERSION = 1;

    public BillingRulesUpdatedV1 {
        if (partyId == null || partyId.isBlank()) {
            throw new IllegalArgumentException("partyId must not be blank");
        }
    }
}
