package com.positivity.accounting.internal.entity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.shared.id.UUIDv7Generator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

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
@Table(name = "accounting_event", indexes = {
        @Index(name = "idx_accounting_event_type", columnList = "event_type"),
        @Index(name = "idx_accounting_event_status", columnList = "status"),
        @Index(name = "idx_accounting_event_transaction_date", columnList = "transaction_date"),
        @Index(name = "idx_accounting_event_received_at", columnList = "received_at"),
        @Index(name = "idx_accounting_event_org_status", columnList = "organization_id, status")
})
public class AccountingEvent {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "event_id", nullable = false, columnDefinition = "UUID")
    private UUID eventId;

    @PrePersist
    public void onPrePersist() {
        if (eventId == null) {
            eventId = UUIDv7Generator.generate();
        }
        this.receivedAt = Instant.now();
    }

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "organization_id", columnDefinition = "UUID")
    private UUID organizationId;

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

}
