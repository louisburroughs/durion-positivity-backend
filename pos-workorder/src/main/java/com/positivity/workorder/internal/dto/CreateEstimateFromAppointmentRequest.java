package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to create an estimate from an appointment")
public class CreateEstimateFromAppointmentRequest {

    @NotNull
    @Schema(
            description = "Idempotency key to prevent duplicate estimate creation",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    private UUID idempotencyKey;

    @NotNull
    @Schema(
            description = "Appointment identifier",
            example = "01960003-0000-7000-8000-000000000011",
            requiredMode = REQUIRED)
    private UUID appointmentId;

    @NotNull
    @Schema(
            description = "Customer identifier",
            example = "01960003-0000-7000-8000-000000000012",
            requiredMode = REQUIRED)
    private UUID customerId;

    @NotNull
    @Schema(
            description = "Vehicle identifier",
            example = "01960003-0000-7000-8000-000000000013",
            requiredMode = REQUIRED)
    private UUID vehicleId;

    @NotNull
    @Schema(
            description = "Location identifier",
            example = "01960003-0000-7000-8000-000000000014",
            requiredMode = REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Requested services captured from appointment context",
            example = "[\"Oil change\",\"Brake inspection\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> requestedServices;
}
