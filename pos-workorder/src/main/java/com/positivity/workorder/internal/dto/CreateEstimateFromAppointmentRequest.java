package com.positivity.workorder.internal.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateEstimateFromAppointmentRequest {

    @NotNull
    private UUID idempotencyKey;

    @NotNull
    private UUID appointmentId;

    @NotNull
    private UUID customerId;

    @NotNull
    private UUID vehicleId;

    @NotNull
    private UUID locationId;

    private List<String> requestedServices;
}