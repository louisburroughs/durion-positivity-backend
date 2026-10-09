package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Record the tenant's registration for an indirect-tax regime (CAP:550 S32c; AW59). The front door checks the
 * justification and the request id, then passes the body to pos-tax, which owns the registry and checks the rest:
 * the country and regime against its configured profiles, the number against the regime's shape. The actor is never
 * a body field.
 */
@Schema(description = "A tax registration to record: the country's regime, the number as printed and its dates")
public record RecordTaxRegistrationRequest(
        @Schema(description = "ISO 3166-1 alpha-2 country", example = "CA", requiredMode = REQUIRED) @Nullable
        String countryCode,

        @Schema(
                description = "A regime the country's tax profile declares",
                example = "GST_HST",
                requiredMode = REQUIRED)
        @Nullable
        String regime,

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
                description = "Why the registration is recorded (at least 10 characters); kept in its history",
                example = "Registered with the tax authority",
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
        return "RecordTaxRegistrationRequest[countryCode=" + countryCode + ", regime=" + regime
                + ", registrationNumber=****, effectiveFrom=" + effectiveFrom + ", effectiveTo=" + effectiveTo
                + ", requestId=" + requestId + "]";
    }
}
