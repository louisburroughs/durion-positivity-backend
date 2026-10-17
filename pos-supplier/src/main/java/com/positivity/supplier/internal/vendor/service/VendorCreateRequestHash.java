package com.positivity.supplier.internal.vendor.service;

import com.positivity.supplier.internal.vendor.service.model.RemitToDto;
import com.positivity.supplier.internal.vendor.service.model.TaxRegistrationDto;
import com.positivity.supplier.internal.vendor.service.model.VendorCreateRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The canonical hash of a vendor-create payload (#2678): every field the create acts on, in a fixed order, so the
 * same request hashes the same and any difference (a registration number, a remit-to line) is told apart. The hash,
 * not the payload, is what is kept: a registration number never lands in the key table.
 */
final class VendorCreateRequestHash {

    private VendorCreateRequestHash() {}

    static @NonNull String of(@NonNull VendorCreateRequest request) {
        StringBuilder canonical = new StringBuilder("VENDOR_CREATE");
        field(canonical, request.vendorNumber());
        field(canonical, request.legalName());
        field(canonical, request.displayName());
        List<TaxRegistrationDto> registrations = request.taxRegistrations();
        canonical.append('|').append(registrations.size());
        for (TaxRegistrationDto registration : registrations) {
            field(canonical, registration.scheme());
            field(canonical, registration.region());
            field(canonical, registration.number());
        }
        RemitToDto remitTo = request.remitTo();
        if (remitTo == null) {
            canonical.append("|-");
        } else {
            field(canonical, remitTo.payeeName());
            field(canonical, remitTo.addressLine1());
            field(canonical, remitTo.addressLine2());
            field(canonical, remitTo.city());
            field(canonical, remitTo.region());
            field(canonical, remitTo.postalCode());
            field(canonical, remitTo.countryCode());
            field(canonical, remitTo.remittanceEmail());
        }
        field(canonical, request.defaultPaymentTerms());
        field(canonical, request.defaultCurrency());
        return sha256(canonical.toString());
    }

    private static void field(StringBuilder canonical, @Nullable String value) {
        canonical.append('|').append(value == null ? "" : value.replace("|", "||"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
