package com.positivity.shopmanager.internal.dto;

import lombok.Data;

/**
 * Request DTO for creating an appointment from an Estimate or Work Order.
 * Includes idempotency support via clientRequestId per DECISION-SHOPMGMT-014.
 * Supports soft conflict override with reason for audit trail per
 * DECISION-SHOPMGMT-007.
 */
@Data
public class AppointmentCreateRequest {
    private String sourceType; // ESTIMATE or WORKORDER (required)
    private String sourceId; // estimateId or workOrderId (required, opaque string)
    private String facilityId; // facility identifier (required, opaque string per DECISION-SHOPMGMT-012)
    private String scheduledStartDateTime; // ISO-8601 with offset (required, per DECISION-SHOPMGMT-015)
    private String scheduledEndDateTime; // ISO-8601 with offset (required, per DECISION-SHOPMGMT-015)
    private String clientRequestId; // UUID (optional but recommended for idempotency per DECISION-SHOPMGMT-014)
    private Boolean overrideSoftConflicts; // boolean, default false (controls conflict override per
                                           // DECISION-SHOPMGMT-002)
    private String overrideReason; // string, required when overrideSoftConflicts=true (audit trail per
                                   // DECISION-SHOPMGMT-007)
}
