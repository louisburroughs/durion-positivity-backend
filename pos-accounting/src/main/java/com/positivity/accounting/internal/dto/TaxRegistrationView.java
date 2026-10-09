package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One tenant tax registration (CAP:550 S32c), as pos-tax answered a write or as accounting's copy holds it. The
 * number is INTERNAL (ADR-0072 Decision 1); {@link #toString()} masks it.
 */
@Schema(description = "A tenant's registration for an indirect-tax regime")
public record TaxRegistrationView(
        @Schema(description = "Registration id (pos-tax's)", requiredMode = REQUIRED) @NonNull
        UUID registrationId,

        @Schema(description = "ISO 3166-1 alpha-2 country", example = "CA", requiredMode = REQUIRED) @NonNull
        String countryCode,

        @Schema(description = "The regime", example = "GST_HST", requiredMode = REQUIRED) @NonNull
        String regime,

        @Schema(description = "The normalised number", requiredMode = REQUIRED) @NonNull
        String registrationNumber,

        @Schema(
                description = "The regime's single region when it lists exactly one, else the country",
                example = "CA",
                requiredMode = REQUIRED)
        @NonNull
        String jurisdictionCode,

        @Schema(description = "Inclusive first day in effect", requiredMode = REQUIRED) @NonNull
        LocalDate effectiveFrom,

        @Schema(description = "Inclusive last day in effect; absent while open-ended", requiredMode = NOT_REQUIRED)
        @Nullable
        LocalDate effectiveTo,

        @Schema(
                description = "SCHEDULED, ACTIVE or ENDED on the reference date (asOf, else today in UTC)",
                example = "ACTIVE",
                requiredMode = REQUIRED)
        @NonNull
        String status,

        @Schema(description = "Version, sent back on a change", example = "0", requiredMode = REQUIRED)
        long version,

        @Schema(description = "When pos-tax last changed it", requiredMode = REQUIRED) @NonNull
        Instant changedAt) {

    /** Every field but the number, which is never logged. */
    @Override
    public @NonNull String toString() {
        return "TaxRegistrationView[registrationId=" + registrationId + ", countryCode=" + countryCode + ", regime="
                + regime + ", registrationNumber=****, jurisdictionCode=" + jurisdictionCode + ", effectiveFrom="
                + effectiveFrom + ", effectiveTo=" + effectiveTo + ", status=" + status + ", version=" + version + "]";
    }
}
