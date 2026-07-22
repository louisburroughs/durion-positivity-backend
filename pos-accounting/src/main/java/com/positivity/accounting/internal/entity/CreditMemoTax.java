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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Per-jurisdiction attribution of a credit memo's reversed tax (issue #996).
 *
 * <p>Frozen at credit-memo creation from the original invoice's {@code ext_invoice_tax}
 * breakdown, proportioned to the credited amount. The rows for one credit memo sum
 * exactly to {@link CreditMemo#getTaxAmountReversed()}, and across every credit memo of
 * an invoice the per-jurisdiction reversals sum exactly to that jurisdiction's collected
 * tax once the invoice is fully credited (the final credit carries the residual).
 *
 * <p>This replaces the report-time pro-rata approximation the T8 sales-tax liability
 * report used to perform ({@code TaxCreditAllocator}); that allocator now only serves
 * credits issued before this table existed.
 *
 * <p>Immutable once written: a credit memo's reversal is a frozen accounting fact, so
 * only {@link #createdAt} is audited (no last-modified column, no update path).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "credit_memo_tax",
        indexes = {@Index(name = "idx_credit_memo_tax_memo", columnList = "credit_memo_id")})
public class CreditMemoTax {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "credit_memo_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID creditMemoId;

    /** Jurisdiction level, mirroring {@link ExtInvoiceTax#getJurisdictionType()} (STATE/COUNTY/CITY/SPECIAL). */
    @Column(name = "jurisdiction_type", nullable = false, length = 32, updatable = false)
    private String jurisdictionType;

    @Column(name = "jurisdiction_code", nullable = false, length = 64, updatable = false)
    private String jurisdictionCode;

    /** Tax reversed in this jurisdiction by this credit memo; non-negative. */
    @Column(name = "tax_amount_reversed", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal taxAmountReversed;

    /**
     * Creation timestamp (ADR-0024). Populated by Spring Data JPA auditing, whose
     * {@code auditingDateTimeProvider} is wired to the injected {@link java.time.Clock}
     * ({@code JpaAuditingConfig}).
     */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
