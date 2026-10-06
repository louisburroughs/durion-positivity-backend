package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The postal address a vendor is paid at (#2516). Never carries bank details (OI-14).
 *
 * @param payeeName the name a cheque is made out to
 * @param addressLine1 first address line
 * @param addressLine2 second address line, when there is one
 * @param city city
 * @param region state, province or region
 * @param postalCode postal or ZIP code
 * @param countryCode ISO 3166-1 alpha-2 country code, upper case
 * @param remittanceEmail where remittance advice is emailed, when given
 */
@Schema(description = "The postal address a vendor is paid at. Never carries bank details.")
public record RemitToDto(
        @Schema(description = "Name a cheque is made out to.", example = "Michelin North America, Inc.") @NonNull
        String payeeName,

        @Schema(description = "First address line.", example = "1 Parkway South") @NonNull
        String addressLine1,

        @Schema(description = "Second address line.", example = "Suite 200") @Nullable
        String addressLine2,

        @Schema(description = "City.", example = "Greenville") @NonNull
        String city,

        @Schema(description = "State, province or region.", example = "SC") @NonNull
        String region,

        @Schema(description = "Postal or ZIP code.", example = "29615") @NonNull
        String postalCode,

        @Schema(description = "ISO 3166-1 alpha-2 country code.", example = "US") @NonNull
        String countryCode,

        @Schema(description = "Where remittance advice is emailed.", example = "ar@michelin.example") @Nullable
        String remittanceEmail) {

    public RemitToDto {
        payeeName = VendorFields.required(payeeName, "remitTo.payeeName", VendorFields.MAX_NAME_LENGTH);
        addressLine1 = VendorFields.required(addressLine1, "remitTo.addressLine1", VendorFields.MAX_NAME_LENGTH);
        addressLine2 = VendorFields.optional(addressLine2, "remitTo.addressLine2", VendorFields.MAX_NAME_LENGTH);
        city = VendorFields.required(city, "remitTo.city", 100);
        region = VendorFields.required(region, "remitTo.region", 100);
        postalCode = VendorFields.required(postalCode, "remitTo.postalCode", 20);
        countryCode = VendorFields.matching(
                countryCode, "remitTo.countryCode", VendorFields.COUNTRY, "an upper-case ISO 3166-1 alpha-2 code");
        if (remittanceEmail != null) {
            remittanceEmail = VendorFields.matching(
                    remittanceEmail, "remitTo.remittanceEmail", VendorFields.EMAIL, "an email address");
        }
    }
}
