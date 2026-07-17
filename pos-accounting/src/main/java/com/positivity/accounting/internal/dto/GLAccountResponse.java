package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.GLAccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Tolerate;

/**
 * DTO for GL Account response with computed status.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLAccount Response</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "GL account response with computed status")
public class GLAccountResponse {

    @Schema(description = "GL account UUID", example = "01960003-0000-7000-8000-000000000001", requiredMode = REQUIRED)
    @NotNull
    private UUID glAccountId;

    @Schema(description = "GL account code", example = "1200-100", requiredMode = NOT_REQUIRED)
    private String accountCode;

    @Schema(description = "GL account display name", example = "Accounts Receivable", requiredMode = NOT_REQUIRED)
    private String accountName;

    @Schema(description = "Account classification", example = "ASSET", requiredMode = NOT_REQUIRED)
    private AccountType accountType;

    @Schema(
            description = "Optional subtype refining accountType for report grouping and posting-config "
                    + "plausibility checks. Null when no refinement applies.",
            example = "UNDEPOSITED_FUNDS",
            requiredMode = NOT_REQUIRED)
    private AccountSubtype accountSubtype;

    @Schema(
            description = "Whether journal entry lines on this account participate in reconciliation "
                    + "(e.g. settlement/bank reconciliation).",
            example = "false",
            requiredMode = REQUIRED)
    private boolean reconcilable;

    @Schema(
            description = "GL account description",
            example = "Primary receivables account",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(
            description = "Parent GL account UUID for hierarchical accounts",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID parentAccountId;

    @Schema(
            description = "Date the account becomes active",
            example = "2026-01-15T10:30:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime activationDate;

    @Schema(
            description = "Date the account becomes inactive",
            example = "2026-06-18T08:00:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime deactivationDate;

    @Schema(
            description = "Derived account status (ACTIVE, INACTIVE, NOT_YET_ACTIVE)",
            example = "ACTIVE",
            requiredMode = NOT_REQUIRED)
    private GLAccountStatus status; // ACTIVE, INACTIVE, NOT_YET_ACTIVE (derived)

    @Tolerate
    public void setStatus(String status) {
        if (status != null) {
            try {
                this.status = GLAccountStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                this.status = null;
            }
        }
    }

    @Schema(description = "Created timestamp", example = "2026-06-18T08:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Created by username", example = "accounting.user", requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(description = "Last modified timestamp", example = "2026-06-18T08:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant modifiedAt;

    @Schema(description = "Last modified by username", example = "accounting.user", requiredMode = NOT_REQUIRED)
    private String modifiedBy;

    @Schema(description = "Optimistic locking version", example = "6", requiredMode = NOT_REQUIRED)
    private Integer version;
}
