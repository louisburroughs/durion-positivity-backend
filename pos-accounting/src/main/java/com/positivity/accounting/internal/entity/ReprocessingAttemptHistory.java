package com.positivity.accounting.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;

import com.positivity.accounting.internal.enums.ReprocessingOutcome;
import com.positivity.shared.id.UUIDv7Generator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Reprocessing Attempt History - tracks all attempts to reprocess a suspended
 * accounting event.
 * 
 * Provides complete audit trail of reprocessing operations including:
 * - Who triggered the reprocessing
 * - When it was attempted
 * - Outcome (SUCCESS or FAILURE)
 * - Detailed outcome information
 * 
 * @see AccountingEvent
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Suspense Queue</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "accountingEvent")
@Entity
@Table(name = "reprocessing_attempt_history", indexes = {
        @Index(name = "idx_reprocessing_event_id", columnList = "event_id"),
        @Index(name = "idx_reprocessing_attempted_at", columnList = "attempted_at"),
        @Index(name = "idx_reprocessing_outcome", columnList = "outcome")
})
public class ReprocessingAttemptHistory {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "attempt_id", nullable = false, columnDefinition = "UUID")
    private UUID attemptId;

    @PrePersist
    public void onPrePersist() {
        if (attemptId == null) {
            attemptId = UUIDv7Generator.generate();
        }
        if (attemptedAt == null) {
            attemptedAt = Instant.now();
        }
    }

    /**
     * Reference to the accounting event being reprocessed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false, columnDefinition = "UUID")
    private AccountingEvent accountingEvent;

    /**
     * Timestamp when reprocessing was attempted.
     */
    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    /**
     * User ID of the person who triggered the reprocessing.
     * Can be a system user for automated retries.
     */
    @Column(name = "triggered_by_user_id", length = 100, nullable = false)
    private String triggeredByUserId;

    /**
     * Outcome of the reprocessing attempt.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20, nullable = false)
    private ReprocessingOutcome outcome;

    /**
     * Detailed information about the outcome.
     * For SUCCESS: may include posting reference or JE ID
     * For FAILURE: includes error message and diagnostic information
     */
    @Column(name = "outcome_details", length = 4000)
    private String outcomeDetails;

    /**
     * Mapping or rule version used during this reprocessing attempt.
     * Useful for tracking which rules were in effect when reprocessing occurred.
     */
    @Column(name = "mapping_version_used", length = 50)
    private String mappingVersionUsed;

    // ========== Constructors ==========

    /**
     * Create a reprocessing attempt record.
     * 
     * @param accountingEvent   the event being reprocessed
     * @param triggeredByUserId the user who triggered the reprocessing
     * @param outcome           the result of the reprocessing attempt
     * @param outcomeDetails    detailed information about the outcome
     */
    public ReprocessingAttemptHistory(
            @NonNull AccountingEvent accountingEvent,
            @NonNull String triggeredByUserId,
            @NonNull ReprocessingOutcome outcome,
            String outcomeDetails) {
        this.accountingEvent = accountingEvent;
        this.triggeredByUserId = triggeredByUserId;
        this.outcome = outcome;
        this.outcomeDetails = outcomeDetails;
    }
}
