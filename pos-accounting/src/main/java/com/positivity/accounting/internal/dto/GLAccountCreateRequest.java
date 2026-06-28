package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating a new GL Account.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLAccount Request</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to create a new GL account")
public class GLAccountCreateRequest {

    @NotBlank(message = "accountCode is required")
    @Pattern(regexp = "^\\d{4}(-\\d{3})?$", message = "accountCode must match #### or ####-###")
    @Schema(description = "Account code in #### or ####-### format", example = "4000", requiredMode = REQUIRED)
    private String accountCode;

    @NotBlank(message = "accountName is required")
    @Schema(description = "Display name of the GL account", example = "Service Revenue", requiredMode = REQUIRED)
    private String accountName;

    @NotNull(message = "accountType is required")
    @Schema(description = "Type of the GL account", example = "REVENUE", requiredMode = REQUIRED)
    private AccountType accountType;

    @Schema(
            description = "Optional description of the account",
            example = "Revenue from service work",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(
            description = "Identifier of the parent account, if any",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID parentAccountId;

    @NotNull(message = "activationDate is required")
    @Schema(
            description = "Date and time the account becomes active (ISO 8601)",
            example = "2026-06-18T08:00:00",
            requiredMode = REQUIRED)
    private LocalDateTime activationDate;
}
