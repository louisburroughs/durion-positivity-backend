package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Set whether a petty-expense category's stated tax is recovered, and which share (CAP:550 S32d item 4).
 *
 * @param taxRecoverable whether the tax stated on the category's receipts is recovered
 * @param recoverablePercent the share recovered, in (0, 100] with at most two decimals; required when recoverable,
 *     absent when not
 * @param justification why, at least 10 characters
 * @param requestId the caller's id for this request; a replay returns the first result
 * @param version the setting version the caller read
 */
@Schema(description = "A petty-expense category's tax recovery: whether, and which share")
public record PettyExpenseCategoryTaxRecoveryRequest(
        @Schema(
                description = "Whether the tax stated on this category's receipts is recovered",
                example = "true",
                requiredMode = REQUIRED)
        @Nullable
        Boolean taxRecoverable,

        @Schema(
                description = "Share of the stated tax that is recovered, in (0, 100] with at most two decimals;"
                        + " required when taxRecoverable is true and absent when it is false",
                example = "50.00",
                requiredMode = NOT_REQUIRED)
        @Nullable
        BigDecimal recoverablePercent,

        @Schema(
                description = "Why the setting changes (at least 10 characters); kept in its history",
                example = "Meals are half recoverable under the regime",
                minLength = 10,
                maxLength = 1000,
                requiredMode = REQUIRED)
        @Nullable
        String justification,

        @Schema(
                description = "Caller-generated UUIDv7 naming this request; a replay returns the first result",
                requiredMode = REQUIRED)
        @Nullable
        UUID requestId,

        @Schema(
                description = "The setting version the caller read (0 for a category never set); another version is"
                        + " 409 OPTIMISTIC_LOCK",
                example = "0",
                requiredMode = REQUIRED)
        @Nullable
        Integer version) {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final int PERCENT_SCALE = 2;

    /**
     * Refuses a body the command cannot act on.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR})
     */
    public void requireValid() {
        if (taxRecoverable == null) {
            throw new InvalidRequestParameterException("taxRecoverable is required");
        }
        if (taxRecoverable) {
            if (recoverablePercent == null
                    || recoverablePercent.signum() <= 0
                    || recoverablePercent.compareTo(HUNDRED) > 0
                    || Math.max(0, recoverablePercent.stripTrailingZeros().scale()) > PERCENT_SCALE) {
                throw new InvalidRequestParameterException(
                        "recoverablePercent is required when taxRecoverable is true, and must be above 0 and at most"
                                + " 100 with at most two decimals");
            }
        } else if (recoverablePercent != null) {
            throw new InvalidRequestParameterException(
                    "recoverablePercent must be absent when taxRecoverable is false");
        }
        CashRequests.requireJustification(justification, "justification");
        if (requestId == null) {
            throw new InvalidRequestParameterException("requestId is required");
        }
        if (version == null || version < 0) {
            throw new InvalidRequestParameterException("version is required and must not be negative");
        }
    }
}
