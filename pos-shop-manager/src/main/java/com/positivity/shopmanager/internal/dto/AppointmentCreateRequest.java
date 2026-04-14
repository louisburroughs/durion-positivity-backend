package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.AppointmentSourceType;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/**
 * Request DTO for creating an appointment from an Estimate or Work Order.
 * Includes idempotency support via clientRequestId per DECISION-SHOPMGMT-014.
 * Supports soft conflict override with reason for audit trail per
 * DECISION-SHOPMGMT-007.
 */
@Data
public class AppointmentCreateRequest {
    @NotNull
    private UUID crmCustomerId;

    @NotNull
    private UUID crmVehicleId;

    @NotNull
    private UUID locationId;

    private String resourceId;

    @NotNull
    private Instant startAt;

    @NotNull
    private Instant endAt;

    @NotNull
    private List<UUID> serviceRequestIds;

    private String workorderLinkRef;

    /**
     * Optional source type indicating which workexec entity originated this
     * booking. When present, {@code sourceId} must also be provided.
     * Source eligibility is validated before the appointment is persisted.
     */
    private AppointmentSourceType sourceType;

    /**
     * External identifier of the originating estimate or work order.
     * Required when {@code sourceType} is set.
     */
    private String sourceId;
}
