package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A petty-expense category's tax recovery after a change (CAP:550 S32d item 4).
 *
 * @param code the category
 * @param taxRecoverable whether its stated tax is recovered
 * @param recoverablePercent the share recovered; null when not recoverable
 * @param version the setting's version, for the next change
 * @param effectiveFrom when the values took effect
 * @param replayed true when this answers a replayed requestId
 */
@Schema(description = "A petty-expense category's tax recovery")
public record PettyExpenseCategoryTaxRecoveryResponse(
        @Schema(description = "Permanent category code", example = "STAFF_MEALS", requiredMode = REQUIRED) @NonNull
        String code,

        @Schema(description = "Whether the tax stated on its receipts is recovered", requiredMode = REQUIRED)
        boolean taxRecoverable,

        @Schema(
                description = "Share of the stated tax recovered; null when not recoverable",
                example = "50.00",
                nullable = true,
                requiredMode = NOT_REQUIRED)
        @Nullable
        BigDecimal recoverablePercent,

        @Schema(
                description = "The setting's version, to send with the next change",
                example = "1",
                requiredMode = REQUIRED)
        int version,

        @Schema(description = "When these values took effect", requiredMode = REQUIRED) @NonNull
        Instant effectiveFrom,

        @Schema(description = "True when this answers a replayed requestId", requiredMode = REQUIRED)
        boolean replayed) {

    /** The same answer, marked as a replay. */
    public @NonNull PettyExpenseCategoryTaxRecoveryResponse asReplay() {
        return new PettyExpenseCategoryTaxRecoveryResponse(
                code, taxRecoverable, recoverablePercent, version, effectiveFrom, true);
    }
}
