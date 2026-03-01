package com.positivity.shopmanager.service;

import com.positivity.shopmanager.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.service.dto.ConflictOverrideResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;

public interface ConflictOverrideService {
    /**
     * Executes a conflict override: flags the appointment and records the override audit trail.
     * Requires the caller to hold the {@code shopmgr.appointment.override} authority (AC-2).
     */
    @PreAuthorize("hasAuthority('shopmgr.appointment.override')")
    @NonNull ConflictOverrideResponse execute(@NonNull ConflictOverrideRequest request);
}
