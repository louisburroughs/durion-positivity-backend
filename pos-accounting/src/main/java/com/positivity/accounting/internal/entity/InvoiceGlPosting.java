package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
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
 * One invoice revenue-recognition posting cycle (issue #1843, ADR-0044 R6): the journal entry
 * pos-accounting posted when an invoice was finalized, and — once the invoice reverted to
 * {@code DRAFT} or was cancelled — the mirror entry that reversed it.
 *
 * <p>This is the idempotency ledger for {@code InvoiceRevenuePostingService}: a row is created in
 * the same transaction as the revenue journal entry, so a redelivered or replayed
 * {@code FINALIZED}/{@code POSTED} fact finds the open row and posts nothing. The pair
 * {@code (invoiceId, finalizedAt)} identifies the cycle — an invoice finalized, reverted and
 * finalized again carries a new {@code finalizedAt} and gets a second row. A row with a null
 * {@link #reversalJournalEntryId} is <em>open</em>; the database allows at most one open row per
 * invoice (partial unique index, V36).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "invoice_gl_posting")
public class InvoiceGlPosting {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "invoice_gl_posting_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID invoiceId;

    /** The invoice finalization instant this cycle belongs to (business time of the revenue entry). */
    @Column(name = "finalized_at", nullable = false, updatable = false)
    private Instant finalizedAt;

    /** The posted revenue journal entry. */
    @Column(name = "journal_entry_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID journalEntryId;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    /** Revenue portion credited ({@code total - tax}); the reversal mirrors this, not the revert fact. */
    @Column(name = "revenue_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal revenueAmount;

    /** Tax portion credited (zero when the invoice carried no tax leg). */
    @Column(name = "tax_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount;

    /** The mirror entry that reversed {@link #journalEntryId}; null while the posting is open. */
    @Column(name = "reversal_journal_entry_id", columnDefinition = "UUID")
    private UUID reversalJournalEntryId;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Whether the revenue entry has not (yet) been reversed. */
    public boolean isOpen() {
        return reversalJournalEntryId == null;
    }
}
