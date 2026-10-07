package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Add a petty-expense category (#2511; SPEC-accounting-workspace §4.6, AW18). */
@Schema(description = "A new petty-expense category: a permanent code, the cashier's label and the expense account")
public record PettyExpenseCategoryCreateRequest(
        @Schema(
                description = "Permanent code, upper case letters, digits and underscores; never OTHER",
                example = "TIRE_DISPOSAL",
                pattern = "^[A-Z0-9_]{1,40}$",
                requiredMode = REQUIRED)
        @Nullable
        String code,

        @Schema(
                description = "The label the cashier sees",
                example = "Tire disposal",
                maxLength = 100,
                requiredMode = REQUIRED)
        @Nullable
        String label,

        @Schema(
                description = "What belongs in the category",
                example = "Disposal fees for scrap tires",
                maxLength = 500,
                requiredMode = NOT_REQUIRED)
        @Nullable
        String examples,

        @Schema(description = "An active EXPENSE account the category posts to", requiredMode = REQUIRED) @Nullable
        UUID glAccountId,

        @Schema(
                description = "Why the category is added (at least 10 characters); kept in its history",
                example = "Cashiers pay the scrap hauler from the drawer",
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

    /** A category code: permanent, upper case letters, digits and underscores (§4.6). */
    public static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{1,40}$");

    /**
     * Refuses a body the command cannot act on.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR})
     */
    public void requireValid() {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new InvalidRequestParameterException(
                    "code is required: 1 to 40 upper case letters, digits or underscores");
        }
        PettyExpenseCategoryUpdateRequest.requireLabel(label, examples);
        if (glAccountId == null) {
            throw new InvalidRequestParameterException("glAccountId is required");
        }
        CashRequests.requireJustification(justification, "justification");
        if (requestId == null) {
            throw new InvalidRequestParameterException("requestId is required");
        }
    }
}
