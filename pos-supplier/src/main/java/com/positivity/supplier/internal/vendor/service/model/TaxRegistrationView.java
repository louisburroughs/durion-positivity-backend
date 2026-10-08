package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One tax registration of a vendor as every read returns it: masked (#2621, Security ruling on #2617,
 * ruling 4). The number is shown only through the audited reveal.
 *
 * @param registrationId stable for the life of the registration; send it back to keep the registration
 * @param scheme the registration scheme
 * @param region the issuing region where the scheme is regional
 * @param last4 the last four alphanumerics of the number; {@code null} when it has fewer than 8
 */
@Schema(
        description = "One tax registration of a vendor, masked: scheme, region and the last four characters."
                + " The number is never returned here.")
public record TaxRegistrationView(
        @Schema(
                description = "Registration identity (UUIDv7); send it back to keep the registration.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c")
        @NonNull
        UUID registrationId,

        @Schema(description = "Registration scheme.", example = "GST_HST") @NonNull
        String scheme,

        @Schema(description = "Issuing region, where the scheme is regional.", example = "ON") @Nullable
        String region,

        @Schema(
                description = "Last four alphanumerics of the number; null when the number has fewer than"
                        + " 8, which a screen shows as on file.",
                example = "0000")
        @Nullable
        String last4) {}
