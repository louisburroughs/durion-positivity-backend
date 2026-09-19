package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.ConflictOverrideService;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manager override of SOFT scheduling conflicts (CAP-326, DECISION-SHOPMGMT-002/-007). The
 * conflicts were recorded when the booking warned and allowed; this endpoint accepts them.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"shop:conflict:override"})
@RequestMapping("/v1/appointments/{appointmentId}/conflict-override")
@Tag(name = "Conflict Override API", description = "Manager acceptance of SOFT scheduling conflicts")
public class ConflictOverrideController {

    private final ConflictOverrideService conflictOverrideService;

    public ConflictOverrideController(ConflictOverrideService conflictOverrideService) {
        this.conflictOverrideService = conflictOverrideService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + ShopPermissions.CONFLICT_OVERRIDE + "')")
    @EmitEvent(id = "SHOPMGR_APPOINTMENT_CONFLICT_OVERRIDE_CREATE", apiVersion = "1")
    @Operation(
            operationId = "executeConflictOverride",
            summary = "Override SOFT scheduling conflicts on an appointment",
            description = """
                    Records a manager's acceptance of one or more SOFT scheduling conflicts already recorded \
                    against the appointment (DECISION-SHOPMGMT-002), writing one immutable override row per \
                    conflict with the acting manager as both overrider and approver (DECISION-SHOPMGMT-007).
                    Use this tool after a booking or reschedule answered with SOFT conflicts and the manager has \
                    decided to keep the appointment as booked; do not use it to force past a HARD conflict, \
                    which is never overridable, and do not use createAssignment with override=true, which \
                    overrides staffing constraints rather than scheduling ones.
                    Preconditions: the caller holds shop:conflict:override and the appointment's location is in \
                    the caller's scope; each conflictId is recorded against this appointment, is SOFT, and does \
                    not already carry an override.
                    Required inputs: conflictIds (at least one) and a non-blank overrideReason.
                    Emits a SHOPMGR_APPOINTMENT_CONFLICT_OVERRIDE_CREATE event.
                    Returns 400 when a conflictId is not recorded against this appointment or the body is \
                    invalid, 403 when the caller lacks the authority or the location is out of scope, 404 when \
                    the appointment cannot be resolved, and 409 either with code SCHEDULING_CONFLICT and the HARD \
                    conflicts in conflicts[] (nothing is written) or with code CONFLICT_ALREADY_OVERRIDDEN.
                    """)
    @ApiResponse(responseCode = "201", description = "Override recorded for every named conflict.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid body, or a conflict not recorded against this appointment.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks shop:conflict:override or the appointment's location is out of scope.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Appointment not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "A named conflict is HARD (code SCHEDULING_CONFLICT with conflicts[], nothing written) or"
                    + " already overridden (code CONFLICT_ALREADY_OVERRIDDEN).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public @NonNull ConflictOverrideResponse executeOverride(
            @Parameter(description = "Appointment ID", required = true) @PathVariable @NonNull UUID appointmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The conflicts being accepted and the justification.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Accept an overtime warning", value = """
                                                                    {"conflictIds":["01960003-0000-7000-8000-000000000010"],
                                                                     "overrideReason":"Customer waiting on-site; second technician arrives at 10:00"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ConflictOverrideRequest request) {
        return conflictOverrideService.execute(appointmentId, request);
    }
}
