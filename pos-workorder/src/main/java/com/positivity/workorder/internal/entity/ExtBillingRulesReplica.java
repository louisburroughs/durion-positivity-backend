package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only billing-rules replica fed by {@code invoice.billing-rules.updated} facts on
 * {@code invoice.events.v1} (ADR-0044 §6, #902).
 *
 * <p>pos-invoice owns these facts; nothing in this module may write the table except the event
 * consumer. Serves purchase-order enforcement (CAP:092 #98), replacing the retired synchronous
 * billing-rules read.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_billing_rules")
public class ExtBillingRulesReplica {

    /** Owner key of the rules row: the commercial account's party id (36-char string). */
    @Id
    @Column(name = "party_id", nullable = false, length = 36)
    private String partyId;

    @Column(name = "purchase_order_required", nullable = false)
    private boolean purchaseOrderRequired;

    @Column(name = "payment_terms_code", length = 50)
    private String paymentTermsCode;

    @Column(name = "invoice_delivery_method", length = 20)
    private String invoiceDeliveryMethod;

    @Column(name = "invoice_grouping_strategy", length = 30)
    private String invoiceGroupingStrategy;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule: the primary key is the owner's
     * party id, stored in its canonical 36-char (UUID) string form by pos-invoice.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
