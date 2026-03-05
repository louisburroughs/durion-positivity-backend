package com.positivity.accounting.internal.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.positivity.shared.id.UUIDv7Id;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Persisted match candidate for ambiguous vendor bill matching.
 *
 * <p>
 * When a vendor invoice matches multiple pending bills (AMBIGUOUS confidence),
 * each scored candidate is persisted so the UI can present a pick-list for
 * manual selection. The operator then calls {@code selectCandidate} to confirm
 * which bill should be matched to the invoice.
 * </p>
 *
 * <p>
 * Lifecycle:
 * <ol>
 * <li>Created when an AMBIGUOUS match is detected during invoice
 * processing</li>
 * <li>Listed by the operator via the match-candidates endpoint</li>
 * <li>One candidate is selected &rarr; the bill transitions from
 * MATCH_EXCEPTION</li>
 * <li>All candidates for the same invoice event are soft-deleted (resolved =
 * true)</li>
 * </ol>
 *
 * @see com.positivity.accounting.service.VendorBillService#selectMatchCandidate
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "vendor_bill_match_candidate", indexes = {
        @Index(name = "idx_match_candidate_invoice_event", columnList = "invoice_event_id"),
        @Index(name = "idx_match_candidate_bill", columnList = "vendor_bill_id"),
        @Index(name = "idx_match_candidate_resolved", columnList = "resolved")
})
public class VendorBillMatchCandidate {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "candidate_id", nullable = false, columnDefinition = "UUID")
    private UUID candidateId;
    /** The invoice event that triggered the ambiguous match. */
    @Column(name = "invoice_event_id", nullable = false)
    private UUID invoiceEventId;

    /** The candidate vendor bill. */
    @Column(name = "vendor_bill_id", nullable = false)
    private UUID vendorBillId;

    /** Vendor ID (denormalized for efficient listing). */
    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    /** Bill number (denormalized for display without join). */
    @Column(name = "bill_number", length = 50)
    private String billNumber;

    /** Bill total amount (denormalized for display). */
    @Column(name = "bill_total_amount", precision = 19, scale = 4)
    private BigDecimal billTotalAmount;

    /** Match score (0–100 composite). */
    @Column(name = "match_score", nullable = false)
    private int matchScore;

    /**
     * Detailed score breakdown (e.g.
     * "amount_match(40);line_item_similarity(0.85=26);…").
     */
    @Column(name = "score_breakdown", length = 1000)
    private String scoreBreakdown;

    /** Whether this candidate set has been resolved (selected or dismissed). */
    @Column(name = "resolved", nullable = false)
    private boolean resolved = false;

    /** The operator who resolved this candidate (null until resolved). */
    @Column(name = "resolved_by", length = 50)
    private String resolvedBy;

    /** When the candidate was resolved. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Whether this specific candidate was the one selected. */
    @Column(name = "selected", nullable = false)
    private boolean selected = false;

    // Audit
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
