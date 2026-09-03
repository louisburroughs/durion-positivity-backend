package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only replica of pos-invoice's invoice documents (ADR-0044 R3, #842), materialized from
 * {@code invoice.events.v1}. The id is assigned by pos-invoice — never generated here. Status
 * values are pos-invoice's lifecycle ({@code DRAFT}/{@code FINALIZED}/{@code POSTED}/{@code
 * ERROR}); AR state (balance due) is derived in accounting, not stored on the replica.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
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

    /**
     * Originating workorder (nullable, #1651): null for invoices with no originating workorder —
     * order-fronted/counter sales, standalone billing, deposit-settlement documents. Such an
     * invoice still belongs in A/R aging, collections, and payment-lag analytics; callers must
     * not assume a non-null value.
     */
    @Column(name = "workorder_id", columnDefinition = "UUID")
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

    /**
     * Collections-aging due date (#993), frozen at finalization by pos-invoice (party payment
     * terms over the finalization business date). Null on drafts and on events predating the
     * enrichment; {@code OLDEST_FIRST} allocation ages by this, falling back to
     * {@link #finalizedAt}.
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /**
     * Deposit-take provenance (#1623), replicated from pos-invoice: non-null marks the document a
     * deposit-take order rendered for the down-payment itself — a contract liability, not a sale —
     * so revenue-shaped measures summing {@link #total} (E2 {@code invoiced}) must exclude it.
     * Values mirror pos-invoice's {@code DepositSourceType} ({@code ESTIMATE}/{@code WORKORDER}/
     * {@code ORDER}); null on ordinary invoices and on rows replicated before the enrichment.
     */
    @Column(name = "deposit_source_type", length = 16)
    private String depositSourceType;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013, generator-owned upstream): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
