package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.internal.enums.CancellationReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Request to cancel an existing appointment with a mandatory reason code")
public class CancelAppointmentRequest {

    @Schema(
            description = "Mandatory cancellation reason code",
            example = "CUSTOMER_REQUEST",
            requiredMode = REQUIRED)
    @NotNull
    private CancellationReasonCode cancellationReason;

    @Schema(
            description = "Optional free-text notes explaining the cancellation",
            example = "Customer rescheduled to next week",
            requiredMode = NOT_REQUIRED)
    @Size(max = 1000)
    private String notes;
}
