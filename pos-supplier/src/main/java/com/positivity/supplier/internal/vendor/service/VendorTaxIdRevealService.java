package com.positivity.supplier.internal.vendor.service;

import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealRecordView;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealRequest;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealResult;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Reveal of a vendor's full tax-registration number, and the audit of every reveal (#2621, Security ruling on
 * #2617, ruling 4). Every other read is masked.
 */
public interface VendorTaxIdRevealService {

    /**
     * Writes the reveal's audit row and returns its outcome, in one transaction that commits on return (ADR-0072
     * Decision 4): {@code REVEALED} with the number, or {@code REASON_REJECTED} / {@code UNREADABLE} with nothing.
     * It never throws after writing the row, so every audited outcome commits.
     *
     * @throws com.positivity.supplier.internal.exception.SupplierNotFoundException
     *     {@code SUPPLIER_VENDOR_NOT_FOUND} or {@code SUPPLIER_VENDOR_TAX_REGISTRATION_NOT_FOUND}; no row is written
     */
    @NonNull
    TaxIdRevealResult reveal(@NonNull UUID vendorId, @NonNull UUID registrationId, @NonNull TaxIdRevealRequest request);

    /** A vendor's reveals, newest first. Never the number or {@code last4}. */
    @NonNull
    PagedResponse<TaxIdRevealRecordView> listReveals(@NonNull UUID vendorId, int page, int size);
}
