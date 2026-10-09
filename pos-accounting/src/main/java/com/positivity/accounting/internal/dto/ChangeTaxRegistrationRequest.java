package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Change a tax registration's number or dates, or end it by setting {@code effectiveTo} (CAP:550 S32c; AW59). The
 * country and regime never change. The front door checks the justification and the request id, then passes the body
 * to pos-tax.
 */
@Schema(description = "A tax registration's new number or dates")
public record ChangeTaxRegistrationRequest(
        @Schema(
                description = "The number as printed; spaces and hyphens are ignored. It must match the regime's"
                        + " configured shape, and is never echoed in an error",
                maxLength = 128,
                requiredMode = REQUIRED)
        @Nullable
        String registrationNumber,

        @Schema(description = "Inclusive first day in effect", example = "2026-01-01", requiredMode = REQUIRED)
        @Nullable
        LocalDate effectiveFrom,

        @Schema(description = "Inclusive last day in effect; absent while open-ended", requiredMode = NOT_REQUIRED)
        @Nullable
        LocalDate effectiveTo,

        @Schema(
                description = "The version the caller read; another one is 409 OPTIMISTIC_LOCK",
                example = "0",
                requiredMode = REQUIRED)
        @Nullable
        Long version,

        @Schema(
                description = "Why the registration changes (at least 10 characters); kept in its history",
                example = "Deregistered at the end of May",
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
     * The front door's own checks (AW59): the justification and the request id.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR})
     */
    public void requireValid() {
        TaxRegistrationRequests.requireJustificationAndRequestId(justification, requestId);
    }

    /** Every field but the number, which is never logged. */
    @Override
    public String toString() {
        return "ChangeTaxRegistrationRequest[registrationNumber=****, effectiveFrom=" + effectiveFrom + ", effectiveTo="
                + effectiveTo + ", version=" + version + ", requestId=" + requestId + "]";
    }
}
