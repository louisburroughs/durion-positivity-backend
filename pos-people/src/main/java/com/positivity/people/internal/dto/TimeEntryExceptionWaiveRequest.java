package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request to waive a time entry exception")
public class TimeEntryExceptionWaiveRequest {

    @NotBlank(message = "waiveReason is required")
    @Schema(description = "Reason for waiving the exception", requiredMode = Schema.RequiredMode.REQUIRED)
    private String waiveReason;
}
