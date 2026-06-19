package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request to create or update a default GL account mapping")
public class DefaultGLMappingRequest {

    @NotBlank(message = "Event type is required")
    @Size(max = 100, message = "Event type cannot exceed 100 characters")
    @Schema(description = "Accounting event type the mapping applies to", example = "INVOICE_POSTED", requiredMode = REQUIRED)
    private String eventType;

    @Nullable
    @Schema(
            description = "Organization scope for the mapping; null for the global default",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID organizationId;

    @NotNull(message = "Debit account ID is required")
    @Schema(
            description = "Identifier of the GL account to debit",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    private UUID debitAccountId;

    @NotNull(message = "Credit account ID is required")
    @Schema(
            description = "Identifier of the GL account to credit",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    private UUID creditAccountId;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    @Schema(description = "Optional description of the mapping", example = "Default mapping for posted invoices", requiredMode = NOT_REQUIRED)
    private String description;

    @Builder.Default
    @Schema(description = "Whether the mapping is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean active = true;
}
