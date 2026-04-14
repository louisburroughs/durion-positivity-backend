package com.positivity.accounting.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
