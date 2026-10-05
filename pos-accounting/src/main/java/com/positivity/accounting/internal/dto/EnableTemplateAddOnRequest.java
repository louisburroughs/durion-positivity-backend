package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Turns an accounting template add-on on for the caller's tenant (#2526, AW30).
 *
 * <p>The fields carry no bean-validation constraints on purpose: the contract answers a bad body
 * with 400 {@code VALIDATION_ERROR} (ADR-0017 §1), which this module produces from
 * {@link InvalidRequestParameterException}; {@code @Valid} would answer {@code ARGUMENT_NOT_VALID}.
 * The controller calls {@link #requireValid()}.
 */
@Schema(description = "Turn an accounting template add-on on for the caller's tenant")
public record EnableTemplateAddOnRequest(
        @Schema(
                description = "Why the tenant needs the add-on (at least 10 characters); kept in the audit log",
                example = "We run a retread plant at the Tulsa shop",
                minLength = 10,
                maxLength = 1000,
                requiredMode = REQUIRED)
        @Nullable
        String justification,

        @Schema(
                description = "Caller-generated UUIDv7 naming this request; a replay changes nothing and"
                        + " returns the current state",
                example = "019a0000-0000-7000-8000-000000000009",
                requiredMode = REQUIRED)
        @Nullable
        UUID requestId) {

    /** Shortest justification accepted, after trimming. */
    public static final int MIN_JUSTIFICATION = 10;

    /** Longest justification accepted. */
    public static final int MAX_JUSTIFICATION = 1000;

    /**
     * Refuses a body the command cannot act on.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR}) when the justification
     *     is missing, shorter than {@value #MIN_JUSTIFICATION} characters or longer than
     *     {@value #MAX_JUSTIFICATION}, or the requestId is missing
     */
    public void requireValid() {
        if (justification == null || justification.trim().length() < MIN_JUSTIFICATION) {
            throw new InvalidRequestParameterException(
                    "justification is required and must be at least " + MIN_JUSTIFICATION + " characters");
        }
        if (justification.length() > MAX_JUSTIFICATION) {
            throw new InvalidRequestParameterException(
                    "justification must not exceed " + MAX_JUSTIFICATION + " characters");
        }
        if (requestId == null) {
            throw new InvalidRequestParameterException("requestId is required");
        }
    }
}
