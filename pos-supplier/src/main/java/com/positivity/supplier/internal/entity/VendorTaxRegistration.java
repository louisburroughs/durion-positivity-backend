package com.positivity.supplier.internal.entity;

import org.jspecify.annotations.Nullable;

/**
 * One tax registration as stored in {@code supplier_vendor.tax_registrations} (jsonb, #2516).
 *
 * @param scheme the registration scheme, e.g. {@code GST_HST}, {@code EIN}
 * @param number the registration number as issued
 * @param region the issuing region where the scheme is regional
 */
public record VendorTaxRegistration(
        String scheme, String number, @Nullable String region) {}
