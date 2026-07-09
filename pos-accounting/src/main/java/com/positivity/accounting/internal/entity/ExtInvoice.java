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
 * Read-only replica of pos-invoice's invoice documents (ADR-0044 R3, #842), materialized from
 * {@code invoice.events.v1}. The id is assigned by pos-invoice — never generated here. Status
 * values are pos-invoice's lifecycle ({@code DRAFT}/{@code FINALIZED}/{@code POSTED}/{@code
 * ERROR}); AR state (balance due) is derived in accounting, not stored on the replica.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "ext_invoice")
public class ExtInvoice {

    @Id
    @Column(name = "invoice_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "invoice_number", length = 64)
    private String invoiceNumber;

    @Column(name = "workorder_id", columnDefinition = "UUID", nullable = false)
    private UUID workorderId;

    @Column(name = "estimate_id", columnDefinition = "UUID")
    private UUID estimateId;

    @Column(name = "location_id", columnDefinition = "UUID")
    private UUID locationId;

    @Column(name = "party_id", length = 64)
    private String partyId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "subtotal", precision = 19, scale = 4)
    private BigDecimal subtotal;

    @Column(name = "tax", precision = 19, scale = 4)
    private BigDecimal tax;

    @Column(name = "total", precision = 19, scale = 4)
    private BigDecimal total;

    @Column(name = "adjustments_amount", precision = 19, scale = 4)
    private BigDecimal adjustmentsAmount;

    @Column(name = "invoice_created_at")
    private Instant invoiceCreatedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
