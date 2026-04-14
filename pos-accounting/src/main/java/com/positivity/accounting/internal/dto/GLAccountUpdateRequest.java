package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for updating an existing GL Account.
 *
 * Only accountName and description can be updated.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLAccount Request</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for updating mutable GL account fields")
public class GLAccountUpdateRequest {

    @Size(max = 100, message = "accountName must not exceed 100 characters")
    @Schema(description = "Updated account name", example = "Accounts Receivable - Trade")
    private String accountName;

    @Size(max = 500, message = "description must not exceed 500 characters")
    @Schema(description = "Updated account description", example = "Primary receivables account")
    private String description;

    @AssertTrue(message = "At least one non-blank field (accountName or description) is required")
    public boolean hasAtLeastOneFieldToUpdate() {
        return (accountName != null && !accountName.isBlank()) || (description != null && !description.isBlank());
    }
}
