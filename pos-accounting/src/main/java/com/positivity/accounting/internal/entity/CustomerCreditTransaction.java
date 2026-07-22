package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One draw-down of a {@link CustomerCredit} — an application against an invoice or a
 * refund to the customer (issue #992).
 *
 * <p>Each row is the subledger counterpart of exactly one relieving journal entry:
 *
 * <ul>
 *   <li>{@code APPLICATION} → Dr Customer Credit Liability (2300) / Cr Accounts Receivable (1200)</li>
 *   <li>{@code REFUND} → Dr Customer Credit Liability (2300) / Cr Undeposited Funds (1090)</li>
 * </ul>
 *
 * <p>The posting-lifecycle columns live here rather than on {@link CustomerCredit} because
 * one credit can be relieved many times, and every relief must be independently idempotent
 * and traceable back to its journal entry. {@link #requestId} is the caller's idempotency
 * key (unique across the table), so a retried apply/refund resolves to this same row
 * instead of relieving the liability twice.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "customer_credit_transaction",
        indexes = {
            @Index(name = "idx_customer_credit_transaction_credit", columnList = "credit_id"),
            @Index(name = "idx_customer_credit_transaction_invoice", columnList = "invoice_id")
        })
public class CustomerCreditTransaction {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "credit_transaction_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID creditTransactionId;

    @Column(name = "credit_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID creditId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20, updatable = false)
    private CustomerCreditTransactionType transactionType;

    /** Invoice settled by an {@code APPLICATION}; always null for a {@code REFUND}. */
    @Column(name = "invoice_id", columnDefinition = "UUID", updatable = false)
    private UUID invoiceId;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    /**
     * Caller-supplied idempotency key. Also the basis of the GL posting idempotency key and
     * the journal entry {@code sourceEventId}, so the subledger row and the ledger entry
     * share one identity across replays.
     */
    @Column(name = "request_id", length = 100, nullable = false, updatable = false)
    private String requestId;

    /** Journal entry that relieved the liability; null until the outbox work item posts. */
    @Column(name = "gl_journal_entry_id", columnDefinition = "UUID")
    private UUID glJournalEntryId;

    /** When the relieving entry posted; null until then. */
    @Column(name = "gl_posted_at")
    private Instant glPostedAt;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, updatable = false)
    private String createdBy;
}
