package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * A scheduling conflict recorded against an appointment (DECISION-SHOPMGMT-002, CAP-326). Only
 * SOFT conflicts appear on an appointment — a HARD one refuses the booking and has no appointment
 * to appear on — so every entry is overridable by a holder of {@code shop:conflict:override}
 * until {@code overridden} is true.
 */
@Value
@Builder
@Schema(description = "A SOFT scheduling conflict the appointment was booked under")
public class AppointmentConflictView {

    @Schema(description = "Conflict id; what POST …/conflict-override names in conflictIds", requiredMode = REQUIRED)
    UUID conflictId;

    @Schema(
            description = "The rule that fired — the API reason code verbatim",
            example = "FACILITY_NEAR_CAPACITY",
            requiredMode = REQUIRED)
    String code;

    @Schema(description = "HARD or SOFT; SOFT on an appointment", example = "SOFT", requiredMode = REQUIRED)
    String severity;

    @Schema(description = "Rendered explanation", requiredMode = REQUIRED)
    String message;

    @Schema(description = "The contended resource, when the rule names one", requiredMode = NOT_REQUIRED)
    String resourceId;

    @Schema(
            description = "Whether a manager may still override it (SOFT and not yet overridden)",
            requiredMode = REQUIRED)
    boolean overridable;

    @Schema(description = "Whether a manager has recorded an override", requiredMode = REQUIRED)
    boolean overridden;
}
