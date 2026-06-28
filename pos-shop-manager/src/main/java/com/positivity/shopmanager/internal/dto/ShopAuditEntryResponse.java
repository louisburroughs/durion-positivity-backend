package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.internal.enums.ShopAuditEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only response DTO for a shop audit trail entry.
 * Story #61 — Audit Trail for Schedule and Assignment Changes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Read-only shop audit trail entry recording a schedule or assignment change")
public class ShopAuditEntryResponse {

    @Schema(
            description = "Unique audit entry identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Type of audit event recorded", example = "SCHEDULE_UPDATED", requiredMode = REQUIRED)
    private ShopAuditEventType eventType;

    @Schema(
            description = "Associated workorder identifier",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private String workorderId;

    @Schema(
            description = "Associated appointment identifier",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private String appointmentId;

    @Schema(
            description = "Mechanic identifier involved in the change",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = NOT_REQUIRED)
    private String mechanicId;

    @Schema(
            description = "Location identifier the change applies to",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private String locationId;

    @Schema(
            description = "User identifier of the actor who made the change",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    private String actorUserId;

    @Schema(
            description = "Human-readable summary of the change",
            example = "Appointment moved from 08:00 to 11:00",
            requiredMode = NOT_REQUIRED)
    private String changeSummaryText;

    @Schema(
            description = "Machine-readable patch describing the change (JSON)",
            example = "{\"startAt\":{\"old\":\"2026-06-18T08:00:00Z\",\"new\":\"2026-06-18T11:00:00Z\"}}",
            requiredMode = NOT_REQUIRED)
    private String changePatch;

    @Schema(
            description = "Reason code associated with the change",
            example = "CUSTOMER_REQUEST",
            requiredMode = NOT_REQUIRED)
    private String reasonCode;

    @Schema(description = "Number of years the entry is retained", example = "7", requiredMode = REQUIRED)
    private int retentionYears;

    @Schema(
            description = "Instant the audit entry was recorded in UTC (ISO-8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    private Instant recordedAt;
}
