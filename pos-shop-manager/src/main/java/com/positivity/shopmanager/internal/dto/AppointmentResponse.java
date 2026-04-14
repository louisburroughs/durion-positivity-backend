package com.positivity.shopmanager.internal.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private UUID appointmentId;
    private String status;
    private UUID locationId;
    private String resourceId;
    private UUID crmCustomerId;
    private UUID crmVehicleId;
    private Instant startAt;
    private Instant endAt;
    private Instant createdAt;
    private String cancellationReason;
    private String cancellationNotes;
    private List<UUID> serviceRequestIds;
    private Map<String, Object> customerSnapshot;
    private Map<String, Object> vehicleSnapshot;
}
