package com.positivity.shopmanager.service;

import com.positivity.shopmanager.internal.exception.SourceNotEligibleException;

/**
 * Service for validating source document eligibility before appointment
 * creation.
 * Per DECISION-SHOPMGMT-013: establishes source eligibility rules and error
 * codes.
 */
public interface SourceEligibilityService {

    /**
     * Validates that an Estimate is eligible for appointment creation.
     *
     * @param estimateId The estimate identifier
     * @param facilityId The facility identifier (for scoping per
     *                   DECISION-SHOPMGMT-012)
     * @throws SourceNotEligibleException if estimate status is not APPROVED or
     *                                    QUOTED
     */
    void validateEstimateEligibility(String estimateId, String facilityId) throws SourceNotEligibleException;

    /**
     * Validates that a Work Order is eligible for appointment creation.
     *
     * @param workOrderId The work order identifier
     * @param facilityId  The facility identifier (for scoping per
     *                    DECISION-SHOPMGMT-012)
     * @throws SourceNotEligibleException if work order status is COMPLETED or
     *                                    CANCELLED
     */
    void validateWorkOrderEligibility(String workOrderId, String facilityId) throws SourceNotEligibleException;

    /**
     * Gets the estimate status for eligibility checks.
     *
     * @param estimateId The estimate identifier
     * @param facilityId The facility identifier (for scoping)
     * @return The estimate status as a string (e.g., APPROVED, QUOTED, DRAFT,
     *         REJECTED)
     */
    String getEstimateStatus(String estimateId, String facilityId);

    /**
     * Gets the work order status for eligibility checks.
     *
     * @param workOrderId The work order identifier
     * @param facilityId  The facility identifier (for scoping)
     * @return The work order status as a string (e.g., OPEN, IN_PROGRESS,
     *         COMPLETED, CANCELLED)
     */
    String getWorkOrderStatus(String workOrderId, String facilityId);

    /**
     * Checks if a source document already has an associated appointment.
     * If so, returns the existing appointment ID (subject to permission checks).
     *
     * @param sourceType ESTIMATE or WORKORDER
     * @param sourceId   The source identifier
     * @param facilityId The facility identifier (for scoping)
     * @return The existing appointmentId, or null if no appointment exists
     */
    String getExistingAppointmentId(String sourceType, String sourceId, String facilityId);
}
