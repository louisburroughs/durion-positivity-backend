package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Creates a vendor (#2516). A remit-to given here is stored as version 1 without approval; any later
 * remit-to, including a first one, is a change request a second person approves.
 *
 * @param vendorNumber the number people will quote; omit to have {@code V-000001}, {@code V-000002}, …
 *     allocated. Upper case, {@code ^[A-Z0-9][A-Z0-9-]{0,29}$}, unique in the tenant, never changed
 * @param legalName the vendor's legal name
 * @param displayName the name screens show
 * @param taxRegistrations tax registrations; omit or empty for none
 * @param remitTo the remit-to address; omit when not yet known
 * @param defaultPaymentTerms {@code DUE_ON_RECEIPT} or {@code NET<n>}, n 1–120
 * @param defaultCurrency ISO 4217 code, upper case
 */
@Schema(description = "Creates a vendor. vendorNumber is allocated when omitted and never changes afterwards.")
public record VendorCreateRequest(
        @Schema(
                description =
                        "Number people quote; omit to allocate V-000001, V-000002, ... Upper case, letters, digits and hyphens, at most 30, unique in the tenant.",
                example = "MICHELIN")
        @Nullable
        String vendorNumber,

        @Schema(description = "Legal name. Never blank.", example = "Michelin North America, Inc.") @NonNull
        String legalName,

        @Schema(description = "Name screens show. Never blank.", example = "Michelin") @NonNull
        String displayName,

        @Schema(description = "Tax registrations; omit or leave empty for none.") @Nullable
        List<TaxRegistrationDto> taxRegistrations,

        @Schema(description = "Remit-to address, stored as version 1 without approval; omit when not yet known.")
        @Nullable
        RemitToDto remitTo,

        @Schema(description = "DUE_ON_RECEIPT or NET<n> with n from 1 to 120.", example = "NET30") @NonNull
        String defaultPaymentTerms,

        @Schema(description = "ISO 4217 currency code, upper case.", example = "USD") @NonNull
        String defaultCurrency) {

    public VendorCreateRequest {
        if (vendorNumber != null) {
            vendorNumber = VendorFields.matching(
                    vendorNumber,
                    "vendorNumber",
                    VendorFields.VENDOR_NUMBER,
                    "1 to 30 upper-case letters, digits or hyphens, starting with a letter or digit");
        }
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
    }
}
