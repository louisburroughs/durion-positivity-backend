package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.enums.TimeEntryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response representation of a time entry")
public class TimeEntryResponse {

    private UUID timeEntryId;
    private UUID personId;
    private UUID workOrderId;
    private Instant startAt;
    private Instant endAt;
    private TimeEntryStatus status;
    private Instant submittedAt;
    private String decisionByUserId;
    private Instant decisionAtUtc;
    private String rejectionReason;
    private Instant createdAt;
    private Instant updatedAt;
}
