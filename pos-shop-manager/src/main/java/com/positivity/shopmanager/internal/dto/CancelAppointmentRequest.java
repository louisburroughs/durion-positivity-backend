package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.CancellationReasonCode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CancelAppointmentRequest {

    @NotNull
    private CancellationReasonCode cancellationReason;

    private String notes;
}
