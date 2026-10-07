package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Relabel a petty-expense category (#2511): the label and examples only; the code never changes. */
@Schema(description = "A petty-expense category's new label and examples")
public record PettyExpenseCategoryUpdateRequest(
        @Schema(
                description = "The label the cashier sees",
                example = "Staff meals and coffee",
                maxLength = 100,
                requiredMode = REQUIRED)
        @Nullable
        String label,

        @Schema(description = "What belongs in the category", maxLength = 500, requiredMode = NOT_REQUIRED) @Nullable
        String examples,

        @Schema(
                description = "The category version the caller read; another version is 409 VERSION_CONFLICT",
                example = "0",
                requiredMode = NOT_REQUIRED)
        @Nullable
        Integer version,

        @Schema(
                description = "Why the label changes (at least 10 characters); kept in its history",
                example = "Cashiers asked for a clearer label",
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

    static final int MAX_LABEL = 100;
    static final int MAX_EXAMPLES = 500;

    /**
     * Refuses a body the command cannot act on.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR})
     */
    public void requireValid() {
        requireLabel(label, examples);
        CashRequests.requireJustification(justification, "justification");
        if (requestId == null) {
            throw new InvalidRequestParameterException("requestId is required");
        }
    }

    static void requireLabel(@Nullable String label, @Nullable String examples) {
        if (label == null || label.isBlank() || label.trim().length() > MAX_LABEL) {
            throw new InvalidRequestParameterException(
                    "label is required and must not exceed " + MAX_LABEL + " characters");
        }
        if (examples != null && examples.trim().length() > MAX_EXAMPLES) {
            throw new InvalidRequestParameterException("examples must not exceed " + MAX_EXAMPLES + " characters");
        }
    }
}
