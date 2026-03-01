package com.positivity.shopmanager.service;

import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.ConflictResponse;
import com.positivity.shopmanager.internal.exception.SchedulingConflictException;
import java.util.UUID;

/**
 * Service for detecting scheduling conflicts during appointment creation.
 * Per DECISION-SHOPMGMT-002/008/011: detects HARD and SOFT conflicts.
 * Per DECISION-SHOPMGMT-008: enforces operating hours constraints.
 * Per DECISION-SHOPMGMT-011: conflict detection is submit-time only.
 */
public interface ConflictDetectionService {

    /**
     * Detects scheduling conflicts for the proposed appointment time.
     * Called during appointment creation after eligibility checks.
     * 
     * @param request       The appointment create request
     * @param correlationId The request correlation ID (for error response)
     * @throws SchedulingConflictException if HARD or SOFT conflicts are detected
     *                                     (caller decides how to handle based on
     *                                     conflict severity and user permissions)
     */
    void detectConflicts(AppointmentCreateRequest request, UUID correlationId) throws SchedulingConflictException;

    /**
     * Checks if the proposed appointment time is within facility operating hours.
     * Per DECISION-SHOPMGMT-008: violations are HARD conflicts.
     * 
     * @param facilityId             The facility identifier
     * @param scheduledStartDateTime ISO-8601 with offset
     * @param scheduledEndDateTime   ISO-8601 with offset
     * @param facilityTimeZoneId     IANA timezone ID
     * @return true if time is within operating hours; false otherwise
     */
    boolean isWithinOperatingHours(String facilityId, String scheduledStartDateTime, String scheduledEndDateTime,
            String facilityTimeZoneId);

    /**
     * Checks for mechanic availability conflicts.
     * Per DECISION-SHOPMGMT-002: can be SOFT conflicts (overridable with
     * permission).
     * 
     * @param facilityId             The facility identifier
     * @param scheduledStartDateTime ISO-8601 with offset
     * @param scheduledEndDateTime   ISO-8601 with offset
     * @return List of mechanic availability conflicts (empty if none)
     */
    java.util.List<ConflictResponse.Conflict> checkMechanicAvailability(String facilityId,
            String scheduledStartDateTime, String scheduledEndDateTime);

    /**
     * Checks for bay/resource availability conflicts.
     * Per DECISION-SHOPMGMT-002: can be SOFT conflicts (overridable with
     * permission).
     * 
     * @param facilityId             The facility identifier
     * @param scheduledStartDateTime ISO-8601 with offset
     * @param scheduledEndDateTime   ISO-8601 with offset
     * @return List of bay availability conflicts (empty if none)
     */
    java.util.List<ConflictResponse.Conflict> checkBayAvailability(String facilityId, String scheduledStartDateTime,
            String scheduledEndDateTime);
}
