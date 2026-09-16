package com.positivity.shopmanager.internal.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.jspecify.annotations.NonNull;

/**
 * A manager's acceptance of one or more SOFT conflicts recorded against the appointment named in
 * the path (CAP-326, DECISION-SHOPMGMT-002/-007). The conflicts themselves carry the rule, the
 * severity and the resource, so the body names them by id and supplies only the justification.
 */
@Value
@Builder
@Jacksonized
@Schema(description = "Manager override of SOFT scheduling conflicts recorded against an appointment")
public class ConflictOverrideRequest {

    @Schema(
            description = "Ids of the scheduling conflicts being accepted. Each must be recorded against the"
                    + " appointment in the path (400 otherwise), be SOFT (409 with the conflict envelope"
                    + " otherwise) and not already overridden (409 CONFLICT_ALREADY_OVERRIDDEN).",
            example = "[\"01960003-0000-7000-8000-000000000010\"]",
            requiredMode = REQUIRED)
    @NonNull
    @NotEmpty
    List<UUID> conflictIds;

    @Schema(
            description = "Non-blank justification, recorded immutably with the acting manager",
            example = "Customer waiting on-site; second technician arrives at 10:00",
            requiredMode = REQUIRED)
    @NonNull
    @NotBlank
    @Size(max = 2000)
    String overrideReason;
}
