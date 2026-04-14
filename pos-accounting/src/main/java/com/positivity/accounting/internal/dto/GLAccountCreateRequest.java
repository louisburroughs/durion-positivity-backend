package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.AccountType;
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
public class GLAccountCreateRequest {

    @NotBlank(message = "accountCode is required")
    @Pattern(regexp = "^\\d{4}(-\\d{3})?$", message = "accountCode must match #### or ####-###")
    private String accountCode;

    @NotBlank(message = "accountName is required")
    private String accountName;

    @NotNull(message = "accountType is required")
    private AccountType accountType;

    private String description;
    private UUID parentAccountId;

    @NotNull(message = "activationDate is required")
    private LocalDateTime activationDate;
}
