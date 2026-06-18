package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event emitted when a previously completed work session is corrected")
public record WorkSessionCorrectedEvent(
        @Schema(description = "Tenant identifier", example = "01960011-0000-7000-8000-000000000099", requiredMode = Schema.RequiredMode.REQUIRED)
                UUID tenantId,
        @Schema(description = "Identifier of the original session being corrected", example = "01960011-0000-7000-8000-000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
                UUID originalSessionId,
        @Schema(description = "Identifier of the correcting session", example = "01960011-0000-7000-8000-000000000004", requiredMode = Schema.RequiredMode.REQUIRED)
                UUID correctionId,
        @Schema(description = "Employee identifier", example = "01960011-0000-7000-8000-000000000002", requiredMode = Schema.RequiredMode.REQUIRED)
                UUID employeeId,
        @Schema(description = "Corrected session start timestamp", example = "2026-02-16T08:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
                Instant startTime,
        @Schema(description = "Corrected session end timestamp", example = "2026-02-16T17:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
                Instant endTime,
        @Schema(description = "Associated workorder identifier", example = "01960011-0000-7000-8000-000000000030", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                UUID workOrderId,
        @Schema(description = "Reason the session was corrected", example = "Forgot to clock out", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                String correctionReason) {}
