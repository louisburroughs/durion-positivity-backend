package com.positivity.accounting.internal.entity;

import org.hibernate.annotations.JdbcTypeCode;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Accounting Event - canonical event ingestion for JE generation.
 * 
 * Lifecycle: RECEIVED → PROCESSING → PROCESSED (or FAILED/SUSPENDED)
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Accounting Event</a>
 */
@Entity
@Table(name = "accounting_event", indexes = {
        @Index(name = "idx_accounting_event_type", columnList = "event_type"),
        @Index(name = "idx_accounting_event_status", columnList = "status"),
        @Index(name = "idx_accounting_event_transaction_date", columnList = "transaction_date"),
        @Index(name = "idx_accounting_event_received_at", columnList = "received_at")
})
public class AccountingEvent {

    @Id
    @Column(name = "event_id", length = 50, nullable = false)
    private String eventId;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

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

    @Column(name = "journal_entry_id", length = 50)
    private String journalEntryId;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    // Constructors
    public AccountingEvent() {
    }

    // Getters and Setters
    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(LocalDate transactionDate) {
        this.transactionDate = transactionDate;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public AccountingEventStatus getStatus() {
        return status;
    }

    public void setStatus(AccountingEventStatus status) {
        this.status = status;
    }

    public String getJournalEntryId() {
        return journalEntryId;
    }

    public void setJournalEntryId(String journalEntryId) {
        this.journalEntryId = journalEntryId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(Long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    @PrePersist
    protected void onCreate() {
        this.receivedAt = Instant.now();
    }
}
