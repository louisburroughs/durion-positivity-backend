package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A revealed tax-registration number (#2621). Returned only by the reveal, with {@code Cache-Control:
 * no-store}, after its audit row is written in the same transaction.
 *
 * @param registrationId the registration revealed
 * @param scheme its scheme
 * @param region its region
 * @param number the full number. RESTRICTED: never cache, log, or put it in a URL
 */
@Schema(description = "A revealed registration number. RESTRICTED: never cache, log, or put it in a URL.")
public record TaxIdRevealView(
        @Schema(description = "Registration identity (UUIDv7).", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c")
        @NonNull
        UUID registrationId,

        @Schema(description = "Registration scheme.", example = "GST_HST") @NonNull
        String scheme,

        @Schema(description = "Issuing region, where the scheme is regional.", example = "ON") @Nullable
        String region,

        @Schema(description = "The full registration number as issued.", example = "000-00-0000") @NonNull
        String number) {

    /** Never prints the number. */
    @Override
    public String toString() {
        return "TaxIdRevealView[registrationId=" + registrationId + ", scheme=" + scheme + ", region=" + region
                + ", number=<redacted>]";
    }
}
