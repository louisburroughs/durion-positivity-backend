package com.positivity.invoice.internal.entity;

import com.positivity.invoice.internal.enums.InvoiceDeliveryMethod;
import com.positivity.invoice.internal.enums.InvoiceGroupingStrategy;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Billing rules configuration for commercial accounts.
 * System of record: pos-invoice
 * CAP:092 - Preferences & Billing Rules
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@Table(name = "billing_rules", indexes = {
        @Index(name = "idx_billing_rules_party_id", columnList = "party_id", unique = true)
})
public class BillingRules {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "party_id", nullable = false, unique = true, length = 36)
    private String partyId;

    @Column(name = "purchase_order_required", nullable = false)
    private boolean purchaseOrderRequired = false;

    @Column(name = "payment_terms_code", nullable = false, length = 50)
    private String paymentTermsCode = "NET_30";

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_delivery_method", nullable = false, length = 20)
    private InvoiceDeliveryMethod invoiceDeliveryMethod = InvoiceDeliveryMethod.EMAIL;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_grouping_strategy", nullable = false, length = 30)
    private InvoiceGroupingStrategy invoiceGroupingStrategy = InvoiceGroupingStrategy.PER_WORKORDER;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 36)
    private String updatedBy;

}

