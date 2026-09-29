package com.positivity.accounting.internal.bankrec.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.accounting.internal.bankrec.enums.ReadinessCheckCode;
import com.positivity.accounting.internal.bankrec.enums.ReadinessSeverity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * One close-readiness check that fired (SPEC-manual-bank-reconciliation §5.3; story S6, #2305).
 *
 * @param references the check's references as the §5.3 table names them (ids, dates, amounts, counts)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "A close-readiness check that fired, with its severity and references")
public record CloseReadinessCheck(
        @Schema(
                description = "Check code",
                example = "RECONCILIATION_IN_FLIGHT",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NonNull
        ReadinessCheckCode code,

        @Schema(
                description = "Severity under a REQUIRED* policy: BLOCKING, WARNING or INFO",
                example = "BLOCKING",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NonNull
        ReadinessSeverity severity,

        @Schema(
                description = "Human-readable explanation",
                example = "1 IN_PROGRESS or SUBMITTED reconciliation ends on or before 2026-08-31",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NonNull
        String detail,

        @Schema(
                description = "References named by the check (ids, frontier dates, balances, counts, sums)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NonNull
        Map<String, Object> references) {}
