package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Read model for appointment creation screen.
 * Loaded before the user fills in appointment details.
 * Contains facility context, source document status, and operating hours
 * information.
 */
@Data
@Schema(description = "Read model backing the appointment creation screen with facility and source context")
public class AppointmentCreateModel {

    @Schema(
            description = "Facility identifier",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    private String facilityId; // Facility identifier

    @Schema(description = "Source document type (ESTIMATE or WORKORDER)", example = "ESTIMATE", requiredMode = REQUIRED)
    private String sourceType; // ESTIMATE or WORKORDER

    @Schema(
            description = "Source document identifier (estimateId or workorderId)",
            example = "EST-2026-000045",
            requiredMode = REQUIRED)
    private String sourceId; // Source document identifier (estimateId or workOrderId)

    @Schema(
            description = "IANA timezone ID of the facility",
            example = "America/New_York",
            requiredMode = REQUIRED)
    private String facilityTimeZoneId; // IANA timezone ID (e.g., America/New_York)

    @Schema(
            description = "Source document status, read-only; used for eligibility checks",
            example = "APPROVED",
            requiredMode = NOT_REQUIRED)
    private String sourceStatus; // Source document status (read-only; used for eligibility checks)

    @Schema(
            description = "Suggested default start time (ISO-8601 with offset)",
            example = "2026-06-18T08:00:00-04:00",
            requiredMode = NOT_REQUIRED)
    private String suggestedStartDateTime; // Suggested default start time (ISO-8601 with offset, optional)

    @Schema(
            description = "Suggested default end time (ISO-8601 with offset)",
            example = "2026-06-18T10:00:00-04:00",
            requiredMode = NOT_REQUIRED)
    private String suggestedEndDateTime; // Suggested default end time (ISO-8601 with offset, optional)

    @Schema(description = "Facility opening time", example = "08:00:00", requiredMode = NOT_REQUIRED)
    private String businessHoursOpen; // Facility opening time (optional, e.g., "08:00:00")

    @Schema(description = "Facility closing time", example = "17:00:00", requiredMode = NOT_REQUIRED)
    private String businessHoursClose; // Facility closing time (optional, e.g., "17:00:00")

    @Schema(
            description = "Estimate identifier when sourceType is ESTIMATE",
            example = "EST-2026-000045",
            requiredMode = NOT_REQUIRED)
    private String estimateId; // Optional: estimateId if sourceType=ESTIMATE

    @Schema(
            description = "Workorder identifier when sourceType is WORKORDER",
            example = "WO-2026-000123",
            requiredMode = NOT_REQUIRED)
    private String workOrderId; // Optional: workOrderId if sourceType=WORKORDER
}
