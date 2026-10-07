package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Deactivate a petty-expense category (#2511): never deleted, never retroactive. */
@Schema(description = "Why a petty-expense category is deactivated")
public record PettyExpenseCategoryDeactivateRequest(
        @Schema(
                description = "Why (at least 10 characters); kept in its history",
                example = "Folded into shop supplies",
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
        CashRequests.requireJustification(justification, "justification");
        if (requestId == null) {
            throw new InvalidRequestParameterException("requestId is required");
        }
    }
}
