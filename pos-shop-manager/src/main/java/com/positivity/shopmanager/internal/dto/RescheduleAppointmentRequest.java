package com.positivity.shopmanager.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.Data;

@Data
public class RescheduleAppointmentRequest {

    @NotNull
    private Instant newStartAt;

    @NotNull
    private Instant newEndAt;

    private String reason;
}
