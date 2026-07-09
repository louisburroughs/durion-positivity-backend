package com.positivity.accounting.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only replica of pos-customer billing rules (ADR-0044 R3, issue #842). Written exclusively
 * by the {@code customer.events.v1} consumer; accounting business logic only reads it.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "ext_customer_billing_rules")
public class ExtCustomerBillingRules {

    @Id
    @Column(name = "party_id", columnDefinition = "UUID")
    private UUID partyId;

    @Column(name = "po_required")
    private Boolean poRequired;

    @Column(name = "tax_exempt")
    private Boolean taxExempt;

    @Column(name = "credit_hold")
    private Boolean creditHold;

    @Column(name = "auto_pay_enabled")
    private Boolean autoPayEnabled;

    @Column(name = "payment_terms")
    private String paymentTerms;

    @Column(name = "credit_limit")
    private BigDecimal creditLimit;

    @Column(name = "currency")
    private String currency;

    @Column(name = "invoice_delivery_method")
    private String invoiceDeliveryMethod;

    @Column(name = "billing_address_id", columnDefinition = "UUID")
    private UUID billingAddressId;

    @Column(name = "discount_policy_ref")
    private String discountPolicyRef;

    /** Owner's aggregateVersion — stale/out-of-order events are ignored (ADR-0044 §3). */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
