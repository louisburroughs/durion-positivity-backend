package com.positivity.tax.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A change to a tax registration (CAP:550 S32c): its number, its dates, or both, replacing the current values. The
 * country and the regime never change; a registration for another regime is a new one. Ending a registration is
 * setting {@code effectiveTo}; there is no delete. Checked by the service, never by bean validation.
 *
 * @param registrationNumber the number as printed; it must match the regime's shape
 * @param effectiveFrom      inclusive first day in effect
 * @param effectiveTo        inclusive last day in effect; absent while open-ended
 * @param version            the version the caller read
 * @param justification      why, at least 10 characters
 * @param requestId          caller-generated id; a replay returns the first result
 */
@Schema(name = "TaxRegistrationUpdateRequest", description = "A tax registration's new number or dates")
public record TaxRegistrationUpdateRequest(
        @Schema(
                description = "The number as printed; spaces and hyphens are ignored, letters are upper-cased. Never"
                        + " echoed in an error",
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

    /** Every field but the number, which is never logged. */
    @Override
    public String toString() {
        return "TaxRegistrationUpdateRequest[registrationNumber=****, effectiveFrom=" + effectiveFrom + ", effectiveTo="
                + effectiveTo + ", version=" + version + ", requestId=" + requestId + "]";
    }
}
