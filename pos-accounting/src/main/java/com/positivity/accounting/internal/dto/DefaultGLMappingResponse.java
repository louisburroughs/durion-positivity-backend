package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * DTO for default GL account mapping response.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - DefaultGLMapping Response</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response describing a default GL account mapping")
public class DefaultGLMappingResponse {

    @Schema(description = "Unique identifier of the mapping", example = "01960003-0000-7000-8000-000000000001", requiredMode = REQUIRED)
    private UUID mappingId;

    @Schema(description = "Accounting event type the mapping applies to", example = "INVOICE_POSTED", requiredMode = REQUIRED)
    private String eventType;

    @Nullable
    @Schema(
            description = "Organization scope for the mapping; null for the global default",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID organizationId;

    @Schema(description = "Identifier of the GL account to debit", example = "01960003-0000-7000-8000-000000000010", requiredMode = REQUIRED)
    private UUID debitAccountId;

    @Schema(description = "Account code of the debit GL account", example = "1200-100", requiredMode = NOT_REQUIRED)
    private String debitAccountCode;

    @Schema(description = "Account name of the debit GL account", example = "Accounts Receivable", requiredMode = NOT_REQUIRED)
    private String debitAccountName;

    @Schema(description = "Identifier of the GL account to credit", example = "01960003-0000-7000-8000-000000000020", requiredMode = REQUIRED)
    private UUID creditAccountId;

    @Schema(description = "Account code of the credit GL account", example = "4000", requiredMode = NOT_REQUIRED)
    private String creditAccountCode;

    @Schema(description = "Account name of the credit GL account", example = "Service Revenue", requiredMode = NOT_REQUIRED)
    private String creditAccountName;

    @Schema(description = "Optional description of the mapping", example = "Default mapping for posted invoices", requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Whether the mapping is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean active;

    @Schema(description = "Timestamp when the mapping was created (ISO 8601)", example = "2026-06-18T08:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Identifier of the user who created the mapping", example = "user-1042", requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(description = "Timestamp when the mapping was last modified (ISO 8601)", example = "2026-06-18T09:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant modifiedAt;

    @Schema(description = "Identifier of the user who last modified the mapping", example = "user-2087", requiredMode = NOT_REQUIRED)
    private String modifiedBy;
}
