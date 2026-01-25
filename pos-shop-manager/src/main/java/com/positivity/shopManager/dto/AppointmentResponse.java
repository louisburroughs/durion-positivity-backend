package com.positivity.shopManager.dto;

import lombok.Data;

@Data
public class AppointmentResponse {
    private String appointmentId;
    private String appointmentStatus; // SCHEDULED, CANCELLED, etc.
    private String scheduledStartDateTime; // ISO-8601 with offset
    private String scheduledEndDateTime; // ISO-8601 with offset
    private String facilityId;
    private String facilityTimeZoneId; // IANA timezone ID
    private String sourceType; // ESTIMATE or WORKORDER
    private String sourceId; // estimateId or workorderId
    private String createdAt; // ISO-8601 Z
    private String lastUpdatedAt; // ISO-8601 Z
}
