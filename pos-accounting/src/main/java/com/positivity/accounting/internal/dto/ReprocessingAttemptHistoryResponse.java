package com.positivity.accounting.internal.dto;

import java.time.Instant;
import java.util.UUID;

import com.positivity.accounting.internal.enums.ReprocessingOutcome;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for reprocessing attempt history response.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Reprocessing History</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReprocessingAttemptHistoryResponse {

    private UUID attemptId;
    private UUID eventId;
    private Instant attemptedAt;
    private String triggeredByUserId;
    private ReprocessingOutcome outcome;
    private String outcomeDetails;
    private String mappingVersionUsed;
}
