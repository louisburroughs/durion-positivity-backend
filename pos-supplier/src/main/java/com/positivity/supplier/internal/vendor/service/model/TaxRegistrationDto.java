package com.positivity.supplier.internal.vendor.service.model;

import com.positivity.supplier.internal.vendor.VendorTaxRegistrationShapes;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One tax registration of a vendor as a create or update sends it (#2516, #2621). Input only: reads
 * return {@link TaxRegistrationView}, which never carries the number.
 *
 * <ul>
 *   <li><strong>registrationId, no number:</strong> keep the stored registration and its number. Its scheme
 *       and region must equal the stored ones; re-enter the number to change either.
 *   <li><strong>registrationId and number:</strong> replace that registration's number.
 *   <li><strong>no registrationId:</strong> a new registration; the number is required.
 * </ul>
 *
 * <p>{@code scheme} and {@code region} are trimmed and upper-cased here. The vendor service then checks every
 * entry that carries a number against {@link VendorTaxRegistrationShapes} (ADR-0072 Decision 2,
 * Security confirmation on louisburroughs/durion#571), so an attribute stored beside {@code last4} can never
 * hold part of a number.
 *
 * <p>No validation message echoes the number, the scheme or the region (Security ruling on #2617, ruling 4).
 *
 * @param registrationId a stored registration of this vendor; {@code null} for a new one
 * @param scheme the registration scheme, e.g. {@code GST_HST}, {@code QST}, {@code EIN}; never blank
 * @param number the registration number as issued, 1 to 64 characters once trimmed; {@code null} to keep the
 *     stored number of {@code registrationId}
 * @param region the issuing region where the scheme is regional
 */
@Schema(
        description = "One tax registration of a vendor, as a create or update sends it. Reads never return the"
                + " number: send registrationId without number to keep a stored registration.")
public record TaxRegistrationDto(
        @Schema(
                description =
                        "A stored registration of this vendor, to keep or re-key it. Omit for a new" + " registration.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c")
        @Nullable
        UUID registrationId,

        @Schema(description = "Registration scheme. Never blank.", example = "GST_HST") @NonNull
        String scheme,

        @Schema(
                description = "Registration number as issued, 1 to 64 characters. Required for a new"
                        + " registration; omit it to keep the stored number of registrationId. Never"
                        + " returned by a read.",
                example = "000-00-0000")
        @Nullable
        String number,

        @Schema(description = "Issuing region, where the scheme is regional.", example = "ON") @Nullable
        String region) {

    /** Longest accepted number once trimmed. */
    public static final int MAX_NUMBER_LENGTH = 64;

    public TaxRegistrationDto {
        scheme = VendorTaxRegistrationShapes.normalise(VendorFields.required(scheme, "taxRegistrations[].scheme", 32));
        region = VendorFields.optional(region, "taxRegistrations[].region", 32);
        region = VendorTaxRegistrationShapes.normalise(region);
        if (number != null) {
            number = number.strip();
            // Lengths only: the value itself never reaches a message, a field error or a log.
            if (number.isEmpty()) {
                throw VendorFields.invalid("taxRegistrations[].number must not be blank when present");
            }
            if (number.length() > MAX_NUMBER_LENGTH) {
                throw VendorFields.invalid(
                        "taxRegistrations[].number must be at most " + MAX_NUMBER_LENGTH + " characters");
            }
        }
    }

    /** Never prints the number: a record's default {@code toString} would put it in any log of this object. */
    @Override
    public String toString() {
        return "TaxRegistrationDto[registrationId=" + registrationId + ", scheme=" + scheme + ", number="
                + (number == null ? "null" : "<redacted>") + ", region=" + region + "]";
    }
}
