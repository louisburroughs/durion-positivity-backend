package com.positivity.supplier.internal.vendor.service;

import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.vendor.service.model.RemitApprovalRequest;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeRequest;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeStatus;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeView;
import com.positivity.supplier.internal.vendor.service.model.RemitRejectionRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorCreateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorFactReplayResult;
import com.positivity.supplier.internal.vendor.service.model.VendorStatus;
import com.positivity.supplier.internal.vendor.service.model.VendorStatusChangeRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorUpdateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorView;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The vendor master (#2516, ADR-0070 Decision 2, SPEC §4.9). Every committed create, update, status
 * change and remit-to approval queues {@code supplier.vendor.updated} in the same transaction. The
 * actor is always the security-context principal (ADR-0018).
 */
public interface SupplierVendorService {

    /** Vendors of the caller's tenant ordered by number; {@code q} matches number, display or legal name. */
    @NonNull
    PagedResponse<VendorView> listVendors(@Nullable String q, @Nullable VendorStatus status, int page, int size);

    /** One vendor; 404 {@code SUPPLIER_VENDOR_NOT_FOUND} when the tenant has none with that id. */
    @NonNull
    VendorView getVendor(@NonNull UUID vendorId);

    /** Creates an {@code ACTIVE} vendor; allocates {@code V-nnnnnn} when no number is given. */
    @NonNull
    VendorView createVendor(@NonNull VendorCreateRequest request);

    /** Replaces the settable fields; a stale {@code version} is 409 {@code CONFLICT}. */
    @NonNull
    VendorView updateVendor(@NonNull UUID vendorId, @NonNull VendorUpdateRequest request);

    /** {@code ACTIVE → INACTIVE}. The vendor's profiles stay enabled. */
    @NonNull
    VendorView deactivateVendor(@NonNull UUID vendorId, @NonNull VendorStatusChangeRequest request);

    /** {@code INACTIVE → ACTIVE}. */
    @NonNull
    VendorView reactivateVendor(@NonNull UUID vendorId, @NonNull VendorStatusChangeRequest request);

    /** Records a {@code PENDING} remit-to change; nothing is applied or published. */
    @NonNull
    RemitChangeView requestRemitChange(@NonNull UUID vendorId, @NonNull RemitChangeRequest request);

    /** A vendor's remit-to changes, newest first, optionally in one status. */
    @NonNull
    List<RemitChangeView> listRemitChanges(@NonNull UUID vendorId, @Nullable RemitChangeStatus status);

    /** Applies a pending change; refused for the requester (403 {@code SUPPLIER_VENDOR_REMIT_SELF_APPROVAL}). */
    @NonNull
    RemitChangeView approveRemitChange(
            @NonNull UUID vendorId, @NonNull UUID changeId, @NonNull RemitApprovalRequest request);

    /** Refuses a pending change; the vendor is unchanged. */
    @NonNull
    RemitChangeView rejectRemitChange(
            @NonNull UUID vendorId, @NonNull UUID changeId, @NonNull RemitRejectionRequest request);

    /** Re-queues one bounded page of the tenant's vendor facts, in id order, at their current version. */
    @NonNull
    VendorFactReplayResult replayFacts(@Nullable UUID afterVendorId, int limit);
}
