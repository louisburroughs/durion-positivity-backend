package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "A customer follow-up task")
public record FollowUpTaskResponse(
        @Schema(description = "Task identifier", requiredMode = REQUIRED)
        UUID taskId,

        @Schema(description = "Party the follow-up concerns", requiredMode = REQUIRED)
        UUID partyId,

        @Schema(description = "Vehicle the follow-up concerns", requiredMode = NOT_REQUIRED)
        UUID vehicleId,

        @Schema(
                description = "Why the follow-up exists",
                example = "DECLINED_SERVICE_FOLLOWUP",
                allowableValues = {
                    "DECLINED_SERVICE_FOLLOWUP",
                    "SERVICE_DUE_REMINDER",
                    "FLEET_CHECKIN",
                    "CAMPAIGN_RESPONSE",
                    "GENERAL"
                },
                requiredMode = REQUIRED)
        String type,

        @Schema(description = "When it should be worked by", requiredMode = NOT_REQUIRED)
        LocalDate dueDate,

        @Schema(description = "Owning CSR; null means unassigned", requiredMode = NOT_REQUIRED)
        String assignedTo,

        @Schema(
                description = "Task state",
                allowableValues = {"OPEN", "DONE", "DISMISSED"},
                requiredMode = REQUIRED)
        String status,

        @Schema(description = "Workorder it originated from", requiredMode = NOT_REQUIRED)
        String sourceWorkorderId,

        @Schema(description = "Context for whoever works it", requiredMode = NOT_REQUIRED)
        String reason,

        @Schema(description = "What happened when it was worked", requiredMode = NOT_REQUIRED)
        String outcome,

        @Schema(description = "Free-text notes", requiredMode = NOT_REQUIRED)
        String notes,

        @Schema(description = "Workorder booked from the hand-off", requiredMode = NOT_REQUIRED)
        UUID bookedWorkorderId,

        @Schema(description = "Appointment booked from the hand-off", requiredMode = NOT_REQUIRED)
        UUID bookedAppointmentId,

        @Schema(description = "When the task was closed", requiredMode = NOT_REQUIRED)
        Instant closedAt,

        @Schema(description = "Who closed it", requiredMode = NOT_REQUIRED)
        String closedBy,

        @Schema(description = "Creation timestamp", requiredMode = NOT_REQUIRED)
        Instant createdAt) {}
