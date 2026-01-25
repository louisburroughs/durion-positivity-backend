package com.positivity.shopManager.dto;

import lombok.Data;

@Data
public class AppointmentCreateRequest {
    private String sourceType; // ESTIMATE or WORKORDER
    private String sourceId;
    private String facilityId;
    private String scheduledStartDateTime; // ISO-8601 with offset
    private String scheduledEndDateTime; // ISO-8601 with offset
    private Boolean overrideSoftConflicts;
    private String overrideReason;
}
