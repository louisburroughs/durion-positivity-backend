package com.positivity.tax.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A new tax registration (CAP:550 S32c), sent by the pos-accounting front door. Every field is checked by the
 * service, not by bean validation, so no binding error can echo the number. The actor is never a body field: it is
 * the forwarded {@code X-User-Id}.
 *
 * @param countryCode        ISO 3166-1 alpha-2 country whose profile declares the regime
 * @param regime             a regime the country declares
 * @param registrationNumber the number as printed; it must match the regime's shape, and is stored normalised
 * @param effectiveFrom      inclusive first day in effect
 * @param effectiveTo        inclusive last day in effect; absent while open-ended
 * @param justification      why, at least 10 characters; kept in the history
 * @param requestId          caller-generated id; a replay returns the first result
 */
@Schema(name = "TaxRegistrationCreateRequest", description = "A tenant's new registration for an indirect-tax regime")
public record TaxRegistrationCreateRequest(
        @Schema(description = "ISO 3166-1 alpha-2 country", example = "ZZ", requiredMode = REQUIRED) @Nullable
        String countryCode,

        @Schema(
                description = "A regime the country's tax profile declares",
                example = "REGIME_1",
                requiredMode = REQUIRED)
        @Nullable
        String regime,

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
                description = "Why the registration is recorded (at least 10 characters); kept in its history",
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
        return "TaxRegistrationCreateRequest[countryCode=" + countryCode + ", regime=" + regime
                + ", registrationNumber=****, effectiveFrom=" + effectiveFrom + ", effectiveTo=" + effectiveTo
                + ", requestId=" + requestId + "]";
    }
}
