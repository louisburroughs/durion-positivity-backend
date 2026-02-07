package com.positivity.accounting.internal.dto;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for submitting an accounting event for sync ingestion.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Accounting Event Request</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingEventSubmitRequest {

    private UUID eventId;
    private String eventType;
    private UUID organizationId;
    private String sourceSystem;
    private LocalDateTime transactionDate;
    private Map<String, Object> payload;

    /**
     * Convert this request to a Map for service processing.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("eventId", eventId);
        map.put("eventType", eventType);
        map.put("organizationId", organizationId);
        map.put("sourceSystem", sourceSystem);
        map.put("transactionDate", transactionDate != null ? transactionDate.toLocalDate().atStartOfDay() : null);
        map.put("payload", payload);
        return map;
    }
}
