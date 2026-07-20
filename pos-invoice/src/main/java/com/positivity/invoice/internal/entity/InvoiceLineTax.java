package com.positivity.invoice.internal.entity;

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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Persisted per-line × per-jurisdiction tax row for an invoice (story T5a).
 *
 * <p>Materialized wholesale from {@code TaxCalculationResponse.LineItemTax.jurisdictions[]} each
 * time a DRAFT invoice is (re)priced; frozen once the invoice is finalized (the re-price path is
 * guarded to DRAFT only). The rollup companion is {@link InvoiceTaxSummary}. The invariant
 * {@code Invoice.tax == Σ taxAmount} holds across these rows (decision D-T6).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "invoice_line_tax",
        indexes = {@Index(name = "idx_invoice_line_tax_invoice", columnList = "invoice_id")})
public class InvoiceLineTax {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_id", columnDefinition = "UUID", nullable = false)
    private UUID invoiceId;

    /** The taxed line's identifier as sent to pos-tax; nullable for provider rows without one. */
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

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
