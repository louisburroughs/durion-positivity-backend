package com.positivity.people.internal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectTimePeriodRequest {

    @NotBlank(message = "reason is required")
    private String reason;
}
