package com.positivity.accounting.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * DTO for creating or updating a default GL account mapping.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - DefaultGLMapping Request</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefaultGLMappingRequest {

    @NotBlank(message = "Event type is required")
    @Size(max = 100, message = "Event type cannot exceed 100 characters")
    private String eventType;

    @Nullable
    private UUID organizationId;

    @NotNull(message = "Debit account ID is required")
    private UUID debitAccountId;

    @NotNull(message = "Credit account ID is required")
    private UUID creditAccountId;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;

    @Builder.Default
    private Boolean active = true;
}
