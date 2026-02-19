package com.positivity.shopmanager.internal.dto;

import lombok.Data;

/**
 * Response DTO for successful appointment creation (HTTP 201).
 * Includes appointment details, facility timezone for display, and optional
 * notification outcome.
 * Per DECISION-SHOPMGMT-015: timestamps include facility timezone offset for
 * scheduledStartDateTime/EndTime;
 * createdAt/lastUpdatedAt are in UTC (Z).
 */
@Data
public class AppointmentResponse {
    private String appointmentId; // appointment identifier (opaque string)
    private String appointmentStatus; // SCHEDULED, CANCELLED, etc. (opaque; no client-side validation)
    private String scheduledStartDateTime; // ISO-8601 with offset (facility timezone per DECISION-SHOPMGMT-015)
    private String scheduledEndDateTime; // ISO-8601 with offset (facility timezone per DECISION-SHOPMGMT-015)
    private String facilityId; // facility identifier
    private String facilityTimeZoneId; // IANA timezone ID (e.g., America/New_York)
    private String sourceType; // ESTIMATE or WORKORDER
    private String sourceId; // estimateId or workOrderId
    private String notificationOutcomeSummary; // optional, per DECISION-SHOPMGMT-016 (e.g., "Email sent to customer")
    private String createdAt; // ISO-8601 Z (UTC timestamp)
    private String lastUpdatedAt; // ISO-8601 Z (UTC timestamp)
}
