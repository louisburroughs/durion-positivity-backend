package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.service.ConflictOverrideService;
import com.positivity.shopmanager.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.service.dto.ConflictOverrideResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
    @PreAuthorize("hasAnyAuthority('shop:schedule:edit', 'appointments:reschedule')")
    @EmitEvent(id = "SHOPMGR_APPOINTMENT_CONFLICT_OVERRIDE_CREATE", apiVersion = "1")
    @Operation(
            summary = "Execute appointment conflict override",
            description = "Executes a conflict override for the specified appointment when authorized.")
    @ApiResponse(responseCode = "201", description = "Conflict override executed.")
    @ApiResponse(responseCode = "400", description = "Invalid request or appointment ID mismatch.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    @ApiResponse(responseCode = "404", description = "Appointment not found.")
    public @NonNull ConflictOverrideResponse executeOverride(
            @Parameter(description = "Appointment ID", required = true) @PathVariable @NonNull UUID appointmentId,
            @Parameter(description = "Conflict override request payload", required = true) @RequestBody @NonNull
                    ConflictOverrideRequest request) {
        // Validate that path appointmentId is consistent with request body
        // appointmentId
        if (!appointmentId.equals(request.getAppointmentId())) {
            throw new IllegalArgumentException("Path appointmentId does not match request body appointmentId");
        }
        return conflictOverrideService.execute(request);
    }
}
