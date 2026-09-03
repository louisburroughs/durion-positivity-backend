package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Summary view of a workorder for list displays")
public class WorkorderSummary {

    @Schema(
            description = "Unique identifier of the workorder",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    UUID workorderId;

    @Schema(description = "Human-readable workorder number", example = "WO-2024-5001", requiredMode = NOT_REQUIRED)
    String workorderNumber;

    @Schema(description = "Current workorder status", example = "WORK_IN_PROGRESS", requiredMode = NOT_REQUIRED)
    String status;

    @Schema(description = "Name of the customer", example = "John Doe", requiredMode = NOT_REQUIRED)
    String customerName;

    @Schema(
            description = "Human-readable vehicle description",
            example = "2020 Toyota Camry",
            requiredMode = NOT_REQUIRED)
    String vehicleDescription;

    @Schema(description = "Date the workorder is scheduled for", example = "2026-01-15", requiredMode = NOT_REQUIRED)
    LocalDate scheduledDate;

    @Schema(
            description = "Identifier of the assigned mechanic",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    String assignedMechanicId;

    /**
     * The workorder's assigned resource, named type-neutrally (#1656).
     *
     * <p>Replaces the former {@code assignedBayId}, which was filled from {@code resourceId}
     * whatever that id pointed at: a mobile-unit assignment shipped a van's id under a bay-named
     * key that no longer joined to anything in the dispatch board's {@code bays[]} panel. It is
     * always read together with {@link #resourceType} so a consumer never infers the kind of
     * resource from the id.
     */
    @Schema(
            description = "Identifier of the resource assigned to the workorder; read together with "
                    + "resourceType, which says whether it is a bay or a mobile unit",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    String assignedResourceId;

    @Schema(
            description =
                    "Kind of resource assignedResourceId points at. Null exactly when " + "assignedResourceId is null",
            example = "BAY",
            requiredMode = NOT_REQUIRED)
    ResourceType resourceType;

    @Schema(description = "Estimated labor hours for the workorder", example = "2.5", requiredMode = NOT_REQUIRED)
    BigDecimal estimatedLaborHours;
}
