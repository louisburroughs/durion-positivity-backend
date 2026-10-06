package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One tax registration of a vendor (#2516).
 *
 * @param scheme the registration scheme, e.g. {@code GST_HST}, {@code QST}, {@code EIN}; never blank
 * @param number the registration number as issued; never blank
 * @param region the issuing region where the scheme is regional
 */
@Schema(description = "One tax registration of a vendor.")
public record TaxRegistrationDto(
        @Schema(description = "Registration scheme. Never blank.", example = "GST_HST") @NonNull
        String scheme,

        @Schema(description = "Registration number as issued. Never blank.", example = "123456789RT0001") @NonNull
        String number,

        @Schema(description = "Issuing region, where the scheme is regional.", example = "ON") @Nullable
        String region) {

    public TaxRegistrationDto {
        scheme = VendorFields.required(scheme, "taxRegistrations[].scheme", 32);
        number = VendorFields.required(number, "taxRegistrations[].number", 64);
        region = VendorFields.optional(region, "taxRegistrations[].region", 32);
    }
}
