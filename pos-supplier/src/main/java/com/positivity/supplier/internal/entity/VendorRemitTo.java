package com.positivity.supplier.internal.entity;

import org.jspecify.annotations.Nullable;

/**
 * A remit-to postal address as stored in jsonb (#2516): the vendor's approved one, or the one a
 * pending change proposes. Never carries bank details (OI-14).
 */
public record VendorRemitTo(
        String payeeName,
        String addressLine1,
        @Nullable String addressLine2,
        String city,
        String region,
        String postalCode,
        String countryCode,
        @Nullable String remittanceEmail) {}
