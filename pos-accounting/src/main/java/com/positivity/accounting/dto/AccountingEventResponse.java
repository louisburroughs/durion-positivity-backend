package com.positivity.accounting.dto;

import com.positivity.accounting.enums.AccountingEventStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * DTO for accounting event response.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Accounting Event Response</a>
 */
public class AccountingEventResponse {

    private String eventId;
    private String eventType;
    private LocalDate transactionDate;
    private Map<String, Object> payload;
    private AccountingEventStatus status;
    private String journalEntryId;
    private String errorMessage;
    private Instant receivedAt;
    private Instant processedAt;
    private Long sequenceNumber;

    // Constructors
    public AccountingEventResponse() {
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
}
