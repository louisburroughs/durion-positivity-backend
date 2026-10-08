package com.positivity.supplier.internal.vendor.service;

import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealRecordView;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealRequest;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealView;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Reveal of a vendor's full tax-registration number, and the audit of every reveal (#2621, Security ruling on
 * #2617, ruling 4). Every other read is masked.
 */
public interface VendorTaxIdRevealService {

    /**
     * Decrypts one registration's number and returns it, after writing its audit row in the same transaction.
     *
     * @throws com.positivity.supplier.internal.exception.SupplierNotFoundException
     *     {@code SUPPLIER_VENDOR_NOT_FOUND} or {@code SUPPLIER_VENDOR_TAX_REGISTRATION_NOT_FOUND}
     * @throws com.positivity.supplier.internal.exception.VendorTaxIdUnreadableException when the stored
     *     ciphertext cannot be decrypted; the audit row is kept with outcome {@code UNREADABLE}
     */
    @NonNull
    TaxIdRevealView reveal(@NonNull UUID vendorId, @NonNull UUID registrationId, @NonNull TaxIdRevealRequest request);

    /** A vendor's reveals, newest first. Never the number or {@code last4}. */
    @NonNull
    PagedResponse<TaxIdRevealRecordView> listReveals(@NonNull UUID vendorId, int page, int size);
}
