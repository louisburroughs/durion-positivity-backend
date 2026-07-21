package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only replica of pos-invoice's per-line × per-jurisdiction tax breakdown (story T5c,
 * ADR-0044 R3 naming: owner = invoice), materialized from {@code InvoiceUpdatedV1.taxBreakdown}.
 *
 * <p>Unlike {@link ExtInvoice} (which keeps the owner's invoice id verbatim as its PK), these
 * detail rows carry no owner-assigned id, so a local UUIDv7 is generated per row. The rows are
 * replaced wholesale (delete-then-insert) for an invoice whenever a non-stale
 * {@code invoice.invoice.updated} arrives carrying a breakdown; {@code aggregate_version} records
 * the invoice version the rows were materialized from. Accounting plan D1 / T8 read this replica;
 * {@link ExtInvoice#getTax()} remains the scalar rollup.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "ext_invoice_tax",
        indexes = {@Index(name = "idx_ext_invoice_tax_invoice", columnList = "invoice_id")})
public class ExtInvoiceTax {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_id", columnDefinition = "UUID", nullable = false)
    private UUID invoiceId;

    @Column(name = "line_item_id", length = 64)
    private String lineItemId;

    @Column(name = "jurisdiction_type", nullable = false, length = 32)
    private String jurisdictionType;

    @Column(name = "jurisdiction_code", nullable = false, length = 64)
    private String jurisdictionCode;

    @Column(name = "rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal rate;

    @Column(name = "taxable_base", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxableBase;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount;

    @Column(name = "exempt", nullable = false)
    private boolean exempt;

    @Column(name = "exemption_reason_code", length = 32)
    private String exemptionReasonCode;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    /**
     * Last-modification timestamp (ADR-0024). Populated by Spring Data JPA auditing whose
     * {@code auditingDateTimeProvider} is wired to the injected {@link java.time.Clock}
     * ({@code JpaAuditingConfig}), so it matches the deterministic value the invoice-event
     * listener sets from the same clock.
     */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
