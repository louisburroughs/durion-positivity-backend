package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
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
 * REST endpoint for conflict override operations.
 * Access is restricted to users with canonical schedule-editing authorities via
 * {@code @PreAuthorize} on {@link ConflictOverrideService}.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"shop:schedule:edit", "appointments:reschedule"})
@RequestMapping("/v1/appointments/{appointmentId}/conflict-override")
@Tag(name = "Conflict Override API", description = "Operations for appointment conflict override decisions")
public class ConflictOverrideController {

    private final ConflictOverrideService conflictOverrideService;

    public ConflictOverrideController(ConflictOverrideService conflictOverrideService) {
        this.conflictOverrideService = conflictOverrideService;
    }

    /**
     * Executes a conflict override for the given appointment.
     * Returns 403 when the caller lacks the required canonical authority (AC-2).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('" + ShopPermissions.SCHEDULE_EDIT + "', '" + ShopPermissions.APPOINTMENTS_RESCHEDULE
            + "')")
    @EmitEvent(id = "SHOPMGR_APPOINTMENT_CONFLICT_OVERRIDE_CREATE", apiVersion = "1")
    @Operation(
            operationId = "executeConflictOverride",
            summary = "Override a Scheduling Conflict on an Appointment",
            description = """
                    Records a manager-authorized bypass of a detected scheduling conflict, flagging the appointment \
                    as conflict-overridden and persisting an immutable override record with the acting user and \
                    timestamp.
                    Use this tool when a blocking conflict on an appointment must be deliberately accepted; do not \
                    use createAssignment with override=true, which overrides assignment constraints while staffing, \
                    and do not use rescheduleAppointment, which resolves the conflict by moving the appointment \
                    instead.
                    Preconditions: the appointment must exist, and the caller must hold the shop:schedule:edit or \
                    appointments:reschedule authority.
                    Required inputs: appointmentId in the body matching the path parameter, and a non-blank \
                    overrideReason; conflictDetails is an optional JSON string describing the conflict being \
                    bypassed.
                    Emits a SHOPMGR_APPOINTMENT_CONFLICT_OVERRIDE_CREATE event, sets the appointment's \
                    conflict-override flag and stores the override record with the actor resolved from the security \
                    context.
                    Returns 400 when the path and body appointmentId differ or the overrideReason is blank, 404 when \
                    the appointment cannot be resolved, and 403 when the caller lacks the required authority.
                    """)
    @ApiResponse(responseCode = "201", description = "Conflict override executed.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request or appointment ID mismatch.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Appointment not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public @NonNull ConflictOverrideResponse executeOverride(
            @Parameter(description = "Appointment ID", required = true) @PathVariable @NonNull UUID appointmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Override decision naming the appointment, the justification, and an"
                                    + " optional description of the conflict being bypassed.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Override for a waiting customer",
                                                            value = """
                                                                    {"appointmentId":"01960003-0000-7000-8000-000000000001",
                                                                     "overrideReason":"Customer waiting on-site",
                                                                     "conflictDetails":"{\\"type\\":\\"TECHNICIAN_DOUBLE_BOOKED\\",\\"resourceId\\":\\"01960003-0000-7000-8000-000000000010\\"}"}
                                                                    """)))
                    @RequestBody
                    @NonNull
                    ConflictOverrideRequest request) {
        // Validate that path appointmentId is consistent with request body
        // appointmentId
        if (!appointmentId.equals(request.getAppointmentId())) {
            throw new ShopManagerValidationException("Path appointmentId does not match request body appointmentId");
        }
        return conflictOverrideService.execute(request);
    }
}
