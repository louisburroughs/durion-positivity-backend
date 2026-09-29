package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.bankrec.enums.BankRecCloseScope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The tenant's effective bank reconciliation policy (SPEC-manual-bank-reconciliation §5.2; story S6, #2305): the
 * defaults stand in for a setting with no row. Serialized with {@code Include.ALWAYS} so an unset threshold reads
 * as an explicit {@code null}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Effective bank reconciliation policy of the tenant")
public class BankReconciliationPolicyResponse {

    @Schema(
            description = "BANK_REC_CLOSE_POLICY (default REQUIRED_WITH_EXCEPTION)",
            example = "REQUIRED_WITH_EXCEPTION")
    private BankRecClosePolicy closePolicy;

    @Schema(description = "BANK_REC_CLOSE_SCOPE (default BANK_CASH_SUBTYPE)", example = "BANK_CASH_SUBTYPE")
    private BankRecCloseScope closeScope;

    @Schema(description = "BANK_REC_CLOSE_COVERAGE_LAG_DAYS (default 0)", example = "0")
    private Integer closeCoverageLagDays;

    @Schema(description = "BANK_REC_ALLOW_SELF_APPROVAL (default false)", example = "false")
    private Boolean allowSelfApproval;

    @Schema(
            description = "BANK_REC_OTHER_APPROVAL_THRESHOLD in the functional currency; null while unset (every"
                    + " non-residual OTHER adjustment then needs accounting:reconciliation:approve)",
            example = "250.00",
            nullable = true)
    private BigDecimal otherApprovalThreshold;

    @Schema(description = "ISO 4217 functional currency the threshold is expressed in", example = "USD")
    private String currency;

    @Schema(description = "When a setting last changed; null while every setting is at its default", nullable = true)
    private Instant updatedAt;

    @Schema(description = "Who last changed a setting; null while every setting is at its default", nullable = true)
    private String updatedBy;
}
