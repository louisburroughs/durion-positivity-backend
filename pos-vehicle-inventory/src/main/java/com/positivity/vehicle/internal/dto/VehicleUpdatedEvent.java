package com.positivity.vehicle.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Event payload signalling vehicle metadata and mileage updates from workorder completion")
public class VehicleUpdatedEvent {

    @Schema(
            description = "Unique identifier of the event",
            example = "01960003-0000-7000-8000-000000000100",
            requiredMode = REQUIRED)
    @NonNull
    private String eventId;

    @Schema(
            description = "Identifier of the workorder that triggered the update",
            example = "01960003-0000-7000-8000-000000000200",
            requiredMode = REQUIRED)
    @NonNull
    private String workorderId;

    @Schema(
            description = "Identifier of the vehicle being updated",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NonNull
    private String vehicleId;

    @Schema(
            description = "Normalized 17-character Vehicle Identification Number",
            example = "1HGCM82633A004352",
            requiredMode = REQUIRED)
    @NonNull
    private String vinNormalized;

    @Schema(description = "Current odometer mileage of the vehicle", example = "84210", requiredMode = NOT_REQUIRED)
    @Nullable
    private Long currentMileage;

    @Schema(
            description = "Human-readable description of the vehicle",
            example = "2003 Honda Accord EX Sedan",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String description;

    @Schema(description = "Model year of the vehicle", example = "2003", requiredMode = NOT_REQUIRED)
    @Nullable
    private Integer year;

    @Schema(description = "Manufacturer of the vehicle", example = "Honda", requiredMode = NOT_REQUIRED)
    @Nullable
    private String make;

    @Schema(description = "Model of the vehicle", example = "Accord", requiredMode = NOT_REQUIRED)
    @Nullable
    private String model;

    @Schema(
            description = "Origin of the update event",
            example = "WORKORDER_COMPLETION",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String source; // e.g., "WORKORDER_COMPLETION"
}
