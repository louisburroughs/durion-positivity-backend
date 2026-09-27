package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.internal.enums.AppointmentSourceType;
import com.positivity.shopmanager.internal.enums.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/**
 * Request DTO for creating an appointment from an Estimate or Work Order.
 * Includes idempotency support via clientRequestId per DECISION-SHOPMGMT-014.
 * Supports soft conflict override with reason for audit trail per
 * DECISION-SHOPMGMT-007.
 */
@Data
@Schema(description = "Request to create an appointment from an estimate or workorder source document")
public class AppointmentCreateRequest {

    @Schema(
            description = "CRM customer identifier the appointment is booked for",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID crmCustomerId;

    @Schema(
            description = "CRM vehicle identifier the appointment services",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID crmVehicleId;

    @Schema(
            description = "Facility/location identifier where the appointment is scheduled",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "Optional resource (bay or mobile unit) reserved for the appointment",
            example = "BAY-04",
            requiredMode = NOT_REQUIRED)
    private String resourceId;

    /**
     * DECISION-SHOPMGMT-003/-021: which axis {@code resourceId} names. Defaults to {@code
     * UNASSIGNED} when omitted, which runs no resource checks at all — a caller naming a real
     * {@code BAY} or {@code MOBILE_UNIT} must say so to have it validated (DECISION-SHOPMGMT-021).
     */
    @Schema(
            description = "Which axis resourceId names; defaults to UNASSIGNED (no resource checks) when omitted."
                    + " BAY runs the full DECISION-SHOPMGMT-021 eligibility rule; MOBILE_UNIT runs existence,"
                    + " location and active checks only.",
            example = "BAY",
            requiredMode = NOT_REQUIRED)
    private ResourceType resourceType;

    @Schema(
            description = "Appointment start instant in UTC (ISO-8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant startAt;

    @Schema(
            description = "Appointment end instant in UTC (ISO-8601); must be after startAt",
            example = "2026-06-18T10:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant endAt;

    @Schema(
            description = "Service request identifiers included in this appointment (at least one required)",
            example = "[\"01960003-0000-7000-8000-000000000004\"]",
            requiredMode = REQUIRED)
    @NotNull
    @NotEmpty
    private List<UUID> serviceRequestIds;

    @Schema(
            description = "Optional reference linking the appointment to a workorder",
            example = "WO-2026-000123",
            requiredMode = NOT_REQUIRED)
    private String workorderLinkRef;

    /**
     * Optional source type indicating which workexec entity originated this
     * booking. When present, {@code sourceId} must also be provided.
     * Source eligibility is validated before the appointment is persisted.
     */
    @Schema(
            description = "Originating workexec source type; when set, sourceId must also be provided",
            example = "ESTIMATE",
            requiredMode = NOT_REQUIRED)
    private AppointmentSourceType sourceType;

    /**
     * External identifier of the originating estimate or work order.
     * Required when {@code sourceType} is set.
     */
    @Schema(
            description =
                    "External identifier of the originating estimate or workorder; required when sourceType is set",
            example = "EST-2026-000045",
            requiredMode = NOT_REQUIRED)
    private String sourceId;
}
