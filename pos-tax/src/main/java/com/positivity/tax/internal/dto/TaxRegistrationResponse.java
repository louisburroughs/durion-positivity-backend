package com.positivity.tax.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A tax registration as stored (CAP:550 S32c). The number is the normalised, shape-checked form (INTERNAL, ADR-0072
 * Decision 1); {@link #toString()} masks it.
 */
@Schema(name = "TaxRegistrationResponse", description = "A tenant's registration for an indirect-tax regime")
public record TaxRegistrationResponse(
        @Schema(description = "Registration id", requiredMode = REQUIRED) @NonNull
        UUID registrationId,

        @Schema(description = "ISO 3166-1 alpha-2 country", example = "ZZ", requiredMode = REQUIRED) @NonNull
        String countryCode,

        @Schema(description = "The regime", example = "REGIME_1", requiredMode = REQUIRED) @NonNull
        String regime,

        @Schema(description = "The normalised number", requiredMode = REQUIRED) @NonNull
        String registrationNumber,

        @Schema(
                description = "The regime's single region when it lists exactly one, else the country",
                example = "ZZ",
                requiredMode = REQUIRED)
        @NonNull
        String jurisdictionCode,

        @Schema(description = "Inclusive first day in effect", requiredMode = REQUIRED) @NonNull
        LocalDate effectiveFrom,

        @Schema(description = "Inclusive last day in effect; absent while open-ended", requiredMode = NOT_REQUIRED)
        @Nullable
        LocalDate effectiveTo,

        @Schema(
                description = "SCHEDULED, ACTIVE or ENDED on today's UTC date, derived from the dates",
                example = "ACTIVE",
                requiredMode = REQUIRED)
        @NonNull
        String status,

        @Schema(description = "Version, for optimistic locking", example = "0", requiredMode = REQUIRED)
        long version,

        @Schema(description = "When it was created", requiredMode = REQUIRED) @NonNull
        Instant createdAt,

        @Schema(description = "Who created it (user id)", requiredMode = REQUIRED) @NonNull
        String createdBy,

        @Schema(description = "When it last changed", requiredMode = REQUIRED) @NonNull
        Instant updatedAt,

        @Schema(description = "Who last changed it (user id)", requiredMode = REQUIRED) @NonNull
        String updatedBy) {

    /** Every field but the number, which is never logged. */
    @Override
    public @NonNull String toString() {
        return "TaxRegistrationResponse[registrationId=" + registrationId + ", countryCode=" + countryCode
                + ", regime=" + regime + ", registrationNumber=****, jurisdictionCode=" + jurisdictionCode
                + ", effectiveFrom=" + effectiveFrom + ", effectiveTo=" + effectiveTo + ", status=" + status
                + ", version=" + version + "]";
    }
}
