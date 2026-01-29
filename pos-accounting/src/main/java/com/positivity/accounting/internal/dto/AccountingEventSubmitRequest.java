package com.positivity.accounting.internal.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * DTO for submitting an accounting event for sync ingestion.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Accounting Event Request</a>
 */
public class AccountingEventSubmitRequest {

    private String eventId;
    private String eventType;
    private LocalDate transactionDate;
    private Map<String, Object> payload;

    // Constructors
    public AccountingEventSubmitRequest() {
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
}
