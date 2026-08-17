package com.positivity.invoice.internal.entity;

import com.positivity.invoice.internal.enums.InvoiceDeliveryMethod;
import com.positivity.invoice.internal.enums.InvoiceGroupingStrategy;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Billing rules configuration for commercial accounts.
 * System of record: pos-invoice
 * CAP:092 - Preferences & Billing Rules
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@Table(
        name = "billing_rules",
        indexes = {@Index(name = "idx_billing_rules_party_id", columnList = "party_id", unique = true)})
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

    /**
     * Whether this party's work needs a fleet payment authorization before it may start (#1346).
     *
     * <p>A policy of the payer rather than of the job: the same commercial account answers the same
     * way for every workorder it pays for. Deciding it per workorder would make it something an
     * advisor can forget, and an unset gate is an open gate.
     */
    @Column(name = "fleet_authorization_required", nullable = false)
    private boolean fleetAuthorizationRequired = false;

    /**
     * Which fleet program authorises for this party, as a supplier profile alias.
     *
     * <p>Held with the flag because the two are useless apart. A party marked as needing
     * authorization but naming no authoriser would have every workorder blocked at the start gate
     * with nothing able to ask anyone for permission — which is why the database rejects that
     * combination outright.
     */
    @Column(name = "fleet_supplier_ref", length = 100)
    private String fleetSupplierRef;

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
