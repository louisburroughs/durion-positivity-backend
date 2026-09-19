package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    @Schema(
            description = "Display name of the customer; null when the customer is not replicated or has no name",
            example = "John Doe",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    String customerName;

    @Schema(
            description = "Human-readable vehicle description",
            example = "2020 Toyota Camry",
            requiredMode = NOT_REQUIRED)
    String vehicleDescription;

    @Schema(description = "Date the workorder is scheduled for", example = "2026-01-15", requiredMode = NOT_REQUIRED)
    LocalDate scheduledDate;

    /**
     * The technician of record, from the workorder's current {@code technician_assignment} row, or
     * {@code null} when the aggregate holds nobody (#2058).
     *
     * <p>This is the field a client keys {@code assignTechnician} vs {@code reassignTechnician}
     * off. It never falls back to {@link #plannedMechanicIds}: a planned mechanic is not a holder,
     * and the technician endpoints do not recognise one.
     */
    @Schema(
            description = "Identifier of the technician of record - the person who currently holds this workorder."
                    + " Null when nobody holds it. Non-null means exactly that a current technician assignment"
                    + " exists, which is one half of the ASSIGNED status and not ASSIGNED itself, so an APPROVED"
                    + " workorder may carry one. Key assignTechnician vs reassignTechnician off this field, never"
                    + " off plannedMechanicIds.",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    String assignedMechanicId;

    /**
     * The legacy {@code mechanic_ids} plan (#1658): who is expected to work the job, written by the
     * shopmgmt-sourced assignment-context event and by {@code overrideOperationalContext}.
     *
     * <p>Scheduling intention, never custody (DECISION-INVENTORY-021/022). It confers no technician
     * of record, is not validated against the person or staffing replicas, and is not a crew model.
     * It is shown so a dispatcher can see who a job is penciled in for, and so a planned mechanic on
     * PTO or missing a certification still raises a conflict. The {@code mechanicIds} array on
     * {@code WorkorderUpdatedV1} is this list unioned with {@link #assignedMechanicId}.
     */
    @Schema(
            description = "Mechanics planned onto this workorder by scheduling or a dispatch override. Display and"
                    + " conflict-detection only: it confers no technician of record and must not decide assign vs"
                    + " reassign. Empty when the job carries no plan.",
            requiredMode = NOT_REQUIRED)
    List<String> plannedMechanicIds;

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

    @Schema(
            description = "Service lines in play on the workorder: every line that is neither cancelled nor "
                    + "declined by the customer",
            example = "2",
            requiredMode = NOT_REQUIRED)
    Integer serviceCount;

    @Schema(
            description = "How many of the serviceCount lines are completed",
            example = "1",
            requiredMode = NOT_REQUIRED)
    Integer completedServiceCount;

    @Schema(
            description = "Descriptions of the lines counted by serviceCount, in line order, at most three; "
                    + "blank descriptions are skipped",
            example = "[\"Oil Change - Full Synthetic\", \"Brake Pad Replacement - Front\"]",
            requiredMode = NOT_REQUIRED)
    List<String> serviceDescriptions;

    @Schema(
            description = "Hours worked on the workorder's service lines so far, whatever each line's status; "
                    + "null when none are logged",
            example = "1.5",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    BigDecimal actualLaborHours;
}
