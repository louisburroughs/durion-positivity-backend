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
 * Per-jurisdiction rollup of an invoice's tax (story T5a): one row per jurisdiction, aggregating
 * the {@link InvoiceLineTax} rows across all lines. Rebuilt wholesale alongside the line rows on
 * each DRAFT re-price; frozen after finalization.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "invoice_tax_summary",
        indexes = {@Index(name = "idx_invoice_tax_summary_invoice", columnList = "invoice_id")})
public class InvoiceTaxSummary {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_id", columnDefinition = "UUID", nullable = false)
    private UUID invoiceId;

    @Column(name = "jurisdiction_type", nullable = false, length = 32)
    private String jurisdictionType;

    @Column(name = "jurisdiction_code", nullable = false, length = 64)
    private String jurisdictionCode;

    @Column(name = "taxable_base", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxableBase;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
