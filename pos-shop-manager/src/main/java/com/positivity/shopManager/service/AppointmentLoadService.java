package com.positivity.shopManager.service;

import com.positivity.shopManager.internal.dto.AppointmentCreateModel;

/**
 * Service for loading appointment creation form data.
 * Per DECISION-SHOPMGMT-012: includes facility context and operating hours.
 */
public interface AppointmentLoadService {

    /**
     * Loads the create appointment form model for a source document (Estimate or
     * Work Order).
     * Per DECISION-SHOPMGMT-012: facility scoping enforced.
     * 
     * @param sourceType    ESTIMATE or WORKORDER
     * @param sourceId      The source identifier (estimateId or workOrderId)
     * @param facilityId    The facility identifier (required per
     *                      DECISION-SHOPMGMT-012)
     * @param correlationId The request correlation ID
     * @return AppointmentCreateModel with facility context, timezone, and operating
     *         hours
     * @throws com.positivity.shopManager.internal.exception.SourceNotEligibleException if
     *                                                                         source
     *                                                                         is
     *                                                                         not
     *                                                                         eligible
     */
    AppointmentCreateModel loadCreateModel(String sourceType, String sourceId, String facilityId, String correlationId);

    /**
     * Gets facility timezone information.
     * Per DECISION-SHOPMGMT-015: used for display and input conversion.
     * 
     * @param facilityId The facility identifier
     * @return IANA timezone ID (e.g., America/New_York)
     */
    String getFacilityTimeZoneId(String facilityId);
}
