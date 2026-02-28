package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.service.ConflictOverrideService;
import com.positivity.shopmanager.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.service.dto.ConflictOverrideResponse;
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
 * Access is restricted to users with {@code shopmgr.appointment.override}
 * authority
 * via {@code @PreAuthorize} on {@link ConflictOverrideService}.
 */
@RestController
@RequestMapping("/v1/appointments/{appointmentId}/conflict-override")
public class ConflictOverrideController {

    private final ConflictOverrideService conflictOverrideService;

    public ConflictOverrideController(ConflictOverrideService conflictOverrideService) {
        this.conflictOverrideService = conflictOverrideService;
    }

    /**
     * Executes a conflict override for the given appointment.
     * Returns 403 when the caller lacks {@code shopmgr.appointment.override}
     * (AC-2).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('shopmgr.appointment.override')")
    @EmitEvent(id = "APPOINTMENT_CONFLICT_OVERRIDE_CREATE", apiVersion = "1")
    public @NonNull ConflictOverrideResponse executeOverride(
            @PathVariable @NonNull UUID appointmentId,
            @RequestBody @NonNull ConflictOverrideRequest request) {
        // Merge path appointmentId into request if not set; validate consistency
        if (!appointmentId.equals(request.getAppointmentId())) {
            throw new IllegalArgumentException(
                    "Path appointmentId does not match request body appointmentId");
        }
        return conflictOverrideService.execute(request);
    }
}
