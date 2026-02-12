package com.positivity.accounting.internal.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to resolve an external code to a GL account.
 * Uses temporal matching to find the effective GL mapping at transaction time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GLMappingResolveRequest {

    @NotBlank(message = "Source system is required")
    private String sourceSystem;

    @NotBlank(message = "External code is required")
    private String externalCode;

    @NotNull(message = "Transaction date is required")
    private LocalDateTime transactionDate;
}
