package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Replaces a vendor's settable fields (#2516). Not settable here: {@code vendorNumber} (never
 * changes), {@code remitTo} (a change request a second person approves) and {@code status} (the
 * deactivation and reactivation commands).
 *
 * @param legalName the vendor's legal name
 * @param displayName the name screens show
 * @param taxRegistrations tax registrations, replacing the stored list; omit or empty for none
 * @param defaultPaymentTerms {@code DUE_ON_RECEIPT} or {@code NET<n>}, n 1–120
 * @param defaultCurrency ISO 4217 code, upper case
 * @param version the vendor version the caller read; a stale one is refused with 409 {@code CONFLICT}
 */
@Schema(description = "Replaces a vendor's settable fields. vendorNumber, remitTo and status are changed elsewhere.")
public record VendorUpdateRequest(
        @Schema(description = "Legal name. Never blank.", example = "Michelin North America, Inc.") @NonNull
        String legalName,

        @Schema(description = "Name screens show. Never blank.", example = "Michelin") @NonNull
        String displayName,

        @Schema(description = "Tax registrations, replacing the stored list; omit or leave empty for none.") @Nullable
        List<TaxRegistrationDto> taxRegistrations,

        @Schema(description = "DUE_ON_RECEIPT or NET<n> with n from 1 to 120.", example = "NET45") @NonNull
        String defaultPaymentTerms,

        @Schema(description = "ISO 4217 currency code, upper case.", example = "USD") @NonNull
        String defaultCurrency,

        @Schema(
                description = "Vendor version the caller read; a stale version is refused with 409 CONFLICT.",
                example = "3")
        @NonNull
        Long version) {

    public VendorUpdateRequest {
        legalName = VendorFields.required(legalName, "legalName", VendorFields.MAX_NAME_LENGTH);
        displayName = VendorFields.required(displayName, "displayName", VendorFields.MAX_NAME_LENGTH);
        taxRegistrations = taxRegistrations == null ? List.of() : List.copyOf(taxRegistrations);
        defaultPaymentTerms = VendorFields.matching(
                defaultPaymentTerms,
                "defaultPaymentTerms",
                VendorFields.PAYMENT_TERMS,
                "DUE_ON_RECEIPT or NET<n> with n from 1 to 120");
        defaultCurrency = VendorFields.matching(
                defaultCurrency, "defaultCurrency", VendorFields.CURRENCY, "an upper-case ISO 4217 code");
        if (version == null || version < 0) {
            throw VendorFields.invalid("version is required");
        }
    }
}
