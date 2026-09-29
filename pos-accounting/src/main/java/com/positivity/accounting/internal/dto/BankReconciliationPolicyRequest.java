package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.bankrec.enums.BankRecCloseScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code PUT /v1/accounting/periods/bank-reconciliation-policy} (SPEC-manual-bank-reconciliation §5.2;
 * story S6, #2305): replaces all five settings at once. Every field is required; {@code otherApprovalThreshold}
 * must be present but may be {@code null}, which clears the setting (unset). The constraints document the contract;
 * the service enforces them so every refusal answers the bank reconciliation codes (400 {@code VALIDATION_ERROR} /
 * {@code JUSTIFICATION_REQUIRED}, §5.2).
 */
@Data
@NoArgsConstructor
@Schema(description = "Replaces the tenant's bank reconciliation policy; all six fields are required")
public class BankReconciliationPolicyRequest {

    @NotNull(message = "closePolicy is required")
    @Schema(
            description = "ADVISORY, REQUIRED_WITH_EXCEPTION or REQUIRED",
            example = "REQUIRED_WITH_EXCEPTION",
            requiredMode = REQUIRED)
    private BankRecClosePolicy closePolicy;

    @NotNull(message = "closeScope is required")
    @Schema(
            description = "BANK_CASH_SUBTYPE or ALL_RECONCILABLE",
            example = "BANK_CASH_SUBTYPE",
            requiredMode = REQUIRED)
    private BankRecCloseScope closeScope;

    @NotNull(message = "closeCoverageLagDays is required")
    @Min(value = 0, message = "closeCoverageLagDays must not be negative")
    @Schema(
            description = "Days the reconciled and coverage frontiers may fall short of the period end",
            example = "0",
            requiredMode = REQUIRED)
    private Integer closeCoverageLagDays;

    @NotNull(message = "allowSelfApproval is required")
    @Schema(
            description = "Whether a submitter may approve their own reconciliation",
            example = "false",
            requiredMode = REQUIRED)
    private Boolean allowSelfApproval;

    @DecimalMin(value = "0", message = "otherApprovalThreshold must not be negative")
    @Schema(
            description = "OTHER adjustment amount in the functional currency above which"
                    + " accounting:reconciliation:approve is needed; required, null clears it (unset)",
            example = "250.00",
            nullable = true,
            requiredMode = REQUIRED)
    private BigDecimal otherApprovalThreshold;

    @JsonIgnore
    @Setter(AccessLevel.NONE)
    @Schema(hidden = true)
    private boolean otherApprovalThresholdPresent;

    @NotBlank(message = "justification is required")
    @Size(min = 10, max = 1000, message = "justification must be between 10 and 1000 characters")
    @Schema(
            description = "Why the policy changes; at least 10 characters (400 JUSTIFICATION_REQUIRED otherwise);"
                    + " recorded on every BANK_REC_POLICY_SET audit row",
            example = "Finance sets the OTHER threshold after the Q3 review",
            requiredMode = REQUIRED)
    private String justification;

    /** Records that the field was sent, even as an explicit {@code null}. */
    public void setOtherApprovalThreshold(BigDecimal otherApprovalThreshold) {
        this.otherApprovalThreshold = otherApprovalThreshold;
        this.otherApprovalThresholdPresent = true;
    }
}
