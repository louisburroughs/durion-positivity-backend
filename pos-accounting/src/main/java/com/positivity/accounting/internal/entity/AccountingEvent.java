package com.positivity.accounting.internal.entity;

import java.time.Clock;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.GeneratedValue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import jakarta.persistence.EntityListeners;
/**
 * Accounting Event - canonical event ingestion for JE generation.
 * 
 * Lifecycle: RECEIVED → PROCESSING → PROCESSED (or FAILED/SUSPENDED)
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Accounting Event</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "accounting_event", indexes = {
        @Index(name = "idx_accounting_event_type", columnList = "event_type"),
        @Index(name = "idx_accounting_event_status", columnList = "status"),
        @Index(name = "idx_accounting_event_transaction_date", columnList = "transaction_date"),
        @Index(name = "idx_accounting_event_received_at", columnList = "received_at"),
        @Index(name = "idx_accounting_event_org_status", columnList = "organization_id, status"),
        @Index(name = "idx_accounting_event_source_system", columnList = "source_system")
})
public class AccountingEvent {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "event_id", nullable = false, columnDefinition = "UUID")
    private UUID eventId;

    /**
     * Optimistic locking version field for concurrency control.
     * Prevents concurrent reprocessing from creating duplicate postings (BR-3).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    public void onPrePersist() {
        if (sourceSystem == null || sourceSystem.isBlank()) {
            sourceSystem = "POS_ACCOUNTING_API";
        }
        if (transactionDate == null) {
            transactionDate = LocalDateTime.now(Clock.systemUTC());
        }
        this.receivedAt = Instant.now(Clock.systemUTC());
    }

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "organization_id", columnDefinition = "UUID")
    private UUID organizationId;

    /**
     * Source system that generated this accounting event.
     * Extracted from event payload for efficient querying.
     */
    @Column(name = "source_system", length = 100, nullable = false)
    private String sourceSystem;

    @Column(name = "transaction_date", nullable = false)
    private LocalDateTime transactionDate;

    /**
     * Event payload as JSON object.
     * Contains domain-specific event data for JE generation.
     * Stored as JSON (H2) or JSONB (PostgreSQL) based on dialect.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AccountingEventStatus status = AccountingEventStatus.RECEIVED;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    // ========== Suspense Queue Fields (CAP:055) ==========

    /**
     * Structured failure reason code for suspense entries.
     * Examples: UNMAPPED_EVENT_TYPE, INVALID_MAPPING_VERSION, RULE_CONFLICT
     */
    @Column(name = "failure_reason_code", length = 100)
    private String failureReasonCode;

    /**
     * Detailed failure information for suspense entries.
     * Provides additional context beyond errorMessage.
     */
    @Column(name = "failure_details", length = 4000)
    private String failureDetails;

    /**
     * Number of reprocessing attempts for this event.
     * Incremented each time reprocessing is triggered.
     * Nullable for backward compatibility with existing events.
     */
    @Column(name = "attempt_count")
    private Integer attemptCount;

    /**
     * Final posting reference after successful reprocessing.
     * Links to the JE or posting transaction created from this event.
     */
    @Column(name = "final_posting_reference_id", length = 100)
    private String finalPostingReferenceId;

    /**
     * User ID of the person who resolved/reprocessed this suspense entry.
     */
    @Column(name = "resolved_by_user_id", length = 100)
    private String resolvedByUserId;

    /**
     * Mapping or rule version that was attempted when this event was suspended.
     * Used for diagnostic and reprocessing purposes.
     */
    @Column(name = "mapping_version_attempted", length = 50)
    private String mappingVersionAttempted;

}
