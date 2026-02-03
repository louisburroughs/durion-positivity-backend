package com.positivity.vehicle.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * VehicleUpdated event from message broker (CAP:091 Story #101).
 * Event schema for vehicle metadata and mileage updates.
 * <p>
 * Source: POS workorder completion triggers vehicle mileage + metadata updates.
 * Routing: Vehicle service consumes via message listener.
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleUpdatedEvent {

    @NonNull
    private String eventId;

    @NonNull
    private String workorderId;

    @NonNull
    private String vehicleId;

    @NonNull
    private String vinNormalized;

    @Nullable
    private Long currentMileage;

    @Nullable
    private String description;

    @Nullable
    private Integer year;

    @Nullable
    private String make;

    @Nullable
    private String model;

    @Nullable
    private String source; // e.g., "WORKORDER_COMPLETION"
}
