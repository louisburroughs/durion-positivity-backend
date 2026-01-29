package com.positivity.shopManager.internal.dto;

import lombok.Data;

/**
 * Read model for appointment creation screen.
 * Loaded before the user fills in appointment details.
 * Contains facility context, source document status, and operating hours
 * information.
 */
@Data
public class AppointmentCreateModel {
    private String facilityId; // Facility identifier
    private String sourceType; // ESTIMATE or WORKORDER
    private String sourceId; // Source document identifier (estimateId or workOrderId)
    private String facilityTimeZoneId; // IANA timezone ID (e.g., America/New_York)
    private String sourceStatus; // Source document status (read-only; used for eligibility checks)
    private String suggestedStartDateTime; // Suggested default start time (ISO-8601 with offset, optional)
    private String suggestedEndDateTime; // Suggested default end time (ISO-8601 with offset, optional)
    private String businessHoursOpen; // Facility opening time (optional, e.g., "08:00:00")
    private String businessHoursClose; // Facility closing time (optional, e.g., "17:00:00")
    private String estimateId; // Optional: estimateId if sourceType=ESTIMATE
    private String workOrderId; // Optional: workOrderId if sourceType=WORKORDER
}
