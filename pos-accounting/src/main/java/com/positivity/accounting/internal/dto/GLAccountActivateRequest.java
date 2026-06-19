package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request to activate a GL account")
public class GLAccountActivateRequest {

    @NotNull(message = "effectiveDate is required")
    @Schema(description = "Date the activation takes effect (ISO 8601 date)", example = "2026-06-18", requiredMode = REQUIRED)
    private LocalDate effectiveDate;
}
