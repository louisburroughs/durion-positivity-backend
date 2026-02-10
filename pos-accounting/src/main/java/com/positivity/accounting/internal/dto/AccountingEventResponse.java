package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.AccountingEventStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Tolerate;

/**
 * DTO for accounting event response.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Accounting Event Response</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingEventResponse {

    private UUID eventId;
    private String eventType;
    private LocalDateTime transactionDate;
    private Map<String, Object> payload;
    private AccountingEventStatus status;
    private UUID journalEntryId;
    private String errorMessage;
    private Instant receivedAt;
    private Instant processedAt;
    private Long sequenceNumber;

    // ========== Suspense Queue Fields (CAP:055) ==========
    private String failureReasonCode;
    private String failureDetails;
    private Integer attemptCount;
    private String finalPostingReferenceId;
    private String resolvedByUserId;
    private String mappingVersionAttempted;

    /**
     * Convenience setter that parses a status string into the enum.
     */
    @Tolerate
    public void setStatus(String status) {
        if (status != null) {
            try {
                this.status = AccountingEventStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                this.status = null;
            }
        }
    }
}
