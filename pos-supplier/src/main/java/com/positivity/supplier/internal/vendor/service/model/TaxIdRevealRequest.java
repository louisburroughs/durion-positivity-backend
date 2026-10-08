package com.positivity.supplier.internal.vendor.service.model;

import com.positivity.supplier.internal.exception.SupplierValidationException;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Why a person needs to see a vendor's full tax-registration number (#2621, Security ruling on #2617,
 * ruling 4). The reason is kept on the reveal's audit row.
 *
 * @param reason trimmed, 10 to 500 characters
 */
@Schema(description = "Why the full registration number is needed. Kept on the reveal audit row.")
public record TaxIdRevealRequest(
        @Schema(
                description = "Reason, 10 to 500 characters once trimmed.",
                example = "Verifying W-9 received 2026-10-08")
        @Nullable
        String reason) {

    public static final int MIN_REASON_LENGTH = 10;
    public static final int MAX_REASON_LENGTH = 500;

    public TaxIdRevealRequest {
        reason = reason == null ? null : reason.strip();
        if (reason == null || reason.length() < MIN_REASON_LENGTH) {
            throw new SupplierValidationException(
                    SupplierValidationException.JUSTIFICATION_REQUIRED,
                    "reason must be at least " + MIN_REASON_LENGTH + " characters");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new SupplierValidationException(
                    SupplierValidationException.VALIDATION_ERROR,
                    "reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
    }
}
