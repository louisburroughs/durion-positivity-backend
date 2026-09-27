package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.shopmanager.internal.enums.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

/**
 * Request payload for rescheduling an appointment.
 *
 * <p>
 * CAP-249 Story #11: reason is mandatory (enum); rescheduleReasonNotes is
 * required when {@code reason == OTHER} or when overriding a hard conflict.
 */
@Data
@Schema(description = "Request to reschedule an appointment to a new time window with a mandatory reason")
public class RescheduleAppointmentRequest {

    @Schema(
            description = "New appointment start instant in UTC (ISO-8601)",
            example = "2026-06-19T08:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant newStartAt;

    @Schema(
            description = "New appointment end instant in UTC (ISO-8601); must be after newStartAt",
            example = "2026-06-19T10:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant newEndAt;

    /** Mandatory reschedule reason. */
    @Schema(description = "Mandatory reschedule reason code", example = "CUSTOMER_REQUEST", requiredMode = REQUIRED)
    @NotNull
    private RescheduleReasonCode reason;

    /**
     * Optional free-text notes; required when reason is OTHER or when overriding
     * a hard scheduling conflict.
     */
    @Schema(
            description = "Optional notes; required when reason is OTHER or when overriding a hard conflict",
            example = "Mechanic out sick; moved to next available slot",
            requiredMode = NOT_REQUIRED)
    @Size(max = 1000)
    private String rescheduleReasonNotes;

    /** Whether to notify the customer of this reschedule (defaults to true). */
    @Schema(
            description = "Whether to notify the customer of this reschedule (defaults to true)",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private boolean notifyCustomer = true;

    /**
     * DECISION-SHOPMGMT-022 rule 3: moves the appointment onto a different resource as part of the
     * reschedule, most commonly off one that has gone out of service. Optional; when set (with
     * {@link #newResourceId}, or alone), the NEW resource is resolved and validated exactly as
     * {@code POST /v1/appointments} would (DECISION-SHOPMGMT-021, the same inference and
     * contradiction rules), and the appointment's <em>old</em> resource is not re-validated — only
     * the resource it ends up on is. Leaving both {@code newResourceType} and {@link #newResourceId}
     * absent keeps today's behaviour: the appointment's current resource is re-validated unchanged.
     */
    @Schema(
            description = "Optional new resource axis (BAY, MOBILE_UNIT or UNASSIGNED) to move the appointment "
                    + "onto as part of this reschedule (DECISION-SHOPMGMT-022 rule 3). Present alongside or in "
                    + "place of newResourceId; validated exactly as appointment create validates resourceType. "
                    + "Absent together with newResourceId keeps the appointment on its current resource, "
                    + "re-validated unchanged (today's behaviour).",
            example = "BAY",
            requiredMode = NOT_REQUIRED)
    private ResourceType newResourceType;

    @Schema(
            description = "Optional new resource id to move the appointment onto as part of this reschedule "
                    + "(DECISION-SHOPMGMT-022 rule 3). When newResourceType is omitted it is inferred from "
                    + "whichever replica holds this id, exactly as appointment create infers it. Only the "
                    + "resource the appointment ends up on is validated and conflict-checked — its old resource, "
                    + "if different, is not re-validated by this call.",
            example = "01960003-0000-7000-8000-000000000012",
            requiredMode = NOT_REQUIRED)
    private UUID newResourceId;

    /**
     * DECISION-SHOPMGMT-004: required, and validated non-blank, only when this reschedule is the
     * caller's 3rd or later non-exempt one and the caller holds
     * {@code appointments:reschedule:approve}; ignored otherwise.
     */
    @Schema(
            description = "Manager's reason for approving a reschedule beyond the free allowance "
                    + "(DECISION-SHOPMGMT-004). Required, and must be non-blank, only when this is the 3rd or "
                    + "later reschedule that is not shop-caused; ignored for the first two, or for one that is "
                    + "exempt (reason EQUIPMENT_ISSUE, or the appointment was already affected, "
                    + "DECISION-SHOPMGMT-022).",
            example = "Customer VIP account; manager approved a 3rd reschedule",
            requiredMode = NOT_REQUIRED)
    @Size(max = 1000)
    private String approvalReason;
}
