package com.positivity.shopmanager.service;

import com.positivity.shopmanager.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.service.dto.ConflictOverrideResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;

public interface ConflictOverrideService {
    /**
     * Executes a conflict override: flags the appointment and records the override audit trail.
     * Requires canonical schedule-editing authority (AC-2).
     */
    @PreAuthorize("hasAnyAuthority('shop:schedule:edit', 'appointments:reschedule')")
    @NonNull
    ConflictOverrideResponse execute(@NonNull ConflictOverrideRequest request);
}
