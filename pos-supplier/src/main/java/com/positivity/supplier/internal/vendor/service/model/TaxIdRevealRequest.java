package com.positivity.supplier.internal.vendor.service.model;

import com.positivity.supplier.internal.exception.SupplierValidationException;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Why a person needs to see a vendor's full tax-registration number (#2621, Security ruling on #2617,
 * ruling 4). The reason is kept on the reveal's audit row.
 *
 * <p>Lengths are counted in code points, as PostgreSQL's {@code length()} and {@code varchar(500)} count them, so
 * a reason built of surrogate pairs cannot pass here and then fail the table's CHECK as a 500. {@link #toString}
 * never prints the reason: on the refused path it may hold the very number being revealed.
 *
 * @param reason trimmed, 10 to 500 code points
 */
@Schema(description = "Why the full registration number is needed. Kept on the reveal audit row.")
public record TaxIdRevealRequest(
        @Schema(
                description = "Reason, 10 to 500 characters once trimmed. Must not contain the number itself.",
                example = "Verifying W-9 received 2026-10-08",
                requiredMode = Schema.RequiredMode.REQUIRED,
                minLength = MIN_REASON_LENGTH,
                maxLength = MAX_REASON_LENGTH)
        @Nullable
        String reason) {

    public static final int MIN_REASON_LENGTH = 10;
    public static final int MAX_REASON_LENGTH = 500;

    public TaxIdRevealRequest {
        reason = reason == null ? null : reason.strip();
        int length = reason == null ? 0 : reason.codePointCount(0, reason.length());
        if (reason == null || length < MIN_REASON_LENGTH) {
            throw new SupplierValidationException(
                    SupplierValidationException.JUSTIFICATION_REQUIRED,
                    "reason must be at least " + MIN_REASON_LENGTH + " characters");
        }
        if (length > MAX_REASON_LENGTH) {
            throw new SupplierValidationException(
                    SupplierValidationException.VALIDATION_ERROR,
                    "reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
    }

    /** Never prints the reason: it may hold the number (Spring MVC's DEBUG body log calls this). */
    @Override
    public String toString() {
        return "TaxIdRevealRequest[reason=" + (reason == null ? "null" : "<redacted>") + "]";
    }
}
