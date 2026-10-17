package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
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
 * @param requestId an idempotency key the client mints (UUID, #2678); optional. A retry with the same key and
 *     the same payload answers the original 201 body; the same key with another payload is 409
 *     {@code IDEMPOTENCY_CONFLICT}
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
        String defaultCurrency,

        @Schema(
                description = "Idempotency key the client mints (UUID). Reuse it only for an identical payload: a"
                        + " retry after a timeout or 5xx then answers the original 201 body instead of creating a"
                        + " second vendor; the same key with another payload is 409 IDEMPOTENCY_CONFLICT. Omit for"
                        + " no idempotency.",
                example = "019a0000-0000-7000-8000-000000000101",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        UUID requestId) {

    /** Without an idempotency key. */
    public VendorCreateRequest(
            @Nullable String vendorNumber,
            @NonNull String legalName,
            @NonNull String displayName,
            @Nullable List<TaxRegistrationDto> taxRegistrations,
            @Nullable RemitToDto remitTo,
            @NonNull String defaultPaymentTerms,
            @NonNull String defaultCurrency) {
        this(
                vendorNumber,
                legalName,
                displayName,
                taxRegistrations,
                remitTo,
                defaultPaymentTerms,
                defaultCurrency,
                null);
    }

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
