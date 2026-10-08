package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.VendorApSettingsRequest;
import com.positivity.accounting.internal.dto.VendorRemitToConfirmationRequest;
import com.positivity.accounting.internal.dto.VendorResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The AP vendor reads and the vendor commands that stay accounting's (Issue #816; CAP:550 S24, #2517;
 * SPEC-accounting-workspace §4.9). Vendors are read from accounting's copy of the pos-supplier vendor master, written
 * only by the {@code supplier.vendor.updated} consumer; nothing here writes the copy.
 */
public interface VendorDirectoryService {

    /**
     * Vendors whose display name contains {@code name} (case-insensitive; null or blank: every vendor), of {@code
     * status} (null: either), ordered by name, at most {@code limit} (clamped to a server-side cap).
     */
    @NonNull
    List<VendorResponse> searchVendors(@Nullable String name, @Nullable String status, int limit);

    /** One vendor with its {@code apSettings}; 404 {@code VENDOR_NOT_FOUND} when it is not in the copy. */
    @NonNull
    VendorResponse getVendorById(@NonNull UUID vendorId);

    /**
     * Records that the caller confirmed the vendor's current remit-to version and how (rule 7): 400 {@code
     * VALIDATION_ERROR} without a version, {@code JUSTIFICATION_REQUIRED} under 10 characters, 404 {@code
     * VENDOR_NOT_FOUND}, 409 {@code VENDOR_PAYMENT_DETAILS_CHANGED} for a version that is not the current one. Audited
     * {@code REMIT_TO_CONFIRM}. The confirmer may not pay on it: payment checks that (rule 6).
     */
    @NonNull
    VendorResponse confirmRemitTo(@NonNull UUID vendorId, @NonNull VendorRemitToConfirmationRequest request);

    /**
     * Sets the vendor's AP defaults (rule 10): a field left out is unchanged, an explicit null clears it. 400 {@code
     * VALIDATION_ERROR} with {@code fieldErrors} for a class outside GOODS / EXPENSE, a key that is not an active
     * {@code VENDOR_BILL} key {@code EXPENSE_<CODE>}, EXPENSE without a key, or no {@code requestId}; {@code
     * JUSTIFICATION_REQUIRED} under 10 characters; 404 {@code VENDOR_NOT_FOUND}. Idempotent on {@code requestId}; each
     * change audited {@code AP_VENDOR_SETTINGS_SET}, old to new. Never touches a posted entry.
     */
    @NonNull
    VendorResponse setApSettings(@NonNull UUID vendorId, @NonNull VendorApSettingsRequest request);
}
