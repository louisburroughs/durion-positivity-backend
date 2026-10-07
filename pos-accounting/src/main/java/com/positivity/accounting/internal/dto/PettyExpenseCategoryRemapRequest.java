package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Move a petty-expense category to another expense account from a date (#2511): effective-dated, non-overlapping. */
@Schema(description = "A petty-expense category's new account and the date it takes effect")
public record PettyExpenseCategoryRemapRequest(
        @Schema(description = "An active EXPENSE account", requiredMode = REQUIRED) @Nullable
        UUID glAccountId,

        @Schema(
                description = "The first day the new account applies; the current mapping ends the day before",
                example = "2026-11-01",
                requiredMode = REQUIRED)
        @Nullable
        LocalDate effectiveFrom,

        @Schema(
                description = "Why the account changes (at least 10 characters); kept in its history",
                example = "Supplies get their own account from November",
                minLength = 10,
                maxLength = 1000,
                requiredMode = REQUIRED)
        @Nullable
        String justification,

        @Schema(
                description = "Caller-generated UUIDv7 naming this request; a replay returns the first result",
                requiredMode = REQUIRED)
        @Nullable
        UUID requestId) {

    /**
     * Refuses a body the command cannot act on.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR})
     */
    public void requireValid() {
        if (glAccountId == null) {
            throw new InvalidRequestParameterException("glAccountId is required");
        }
        if (effectiveFrom == null) {
            throw new InvalidRequestParameterException("effectiveFrom is required");
        }
        CashRequests.requireJustification(justification, "justification");
        if (requestId == null) {
            throw new InvalidRequestParameterException("requestId is required");
        }
    }
}
