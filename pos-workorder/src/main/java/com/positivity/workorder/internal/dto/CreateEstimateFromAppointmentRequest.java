package com.positivity.workorder.internal.dto;

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
            example = "550e8400-e29b-41d4-a716-446655440010")
    private UUID idempotencyKey;

    @NotNull
    @Schema(description = "Appointment identifier", example = "550e8400-e29b-41d4-a716-446655440011")
    private UUID appointmentId;

    @NotNull
    @Schema(description = "Customer identifier", example = "550e8400-e29b-41d4-a716-446655440012")
    private UUID customerId;

    @NotNull
    @Schema(description = "Vehicle identifier", example = "550e8400-e29b-41d4-a716-446655440013")
    private UUID vehicleId;

    @NotNull
    @Schema(description = "Location identifier", example = "550e8400-e29b-41d4-a716-446655440014")
    private UUID locationId;

    @Schema(
            description = "Requested services captured from appointment context",
            example = "[\"Oil change\",\"Brake inspection\"]")
    private List<String> requestedServices;
}
