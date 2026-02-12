package com.positivity.accounting.internal.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request DTO for activating a GL Account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GLAccountActivateRequest {

    @NotNull(message = "effectiveDate is required")
    private LocalDate effectiveDate;
}
