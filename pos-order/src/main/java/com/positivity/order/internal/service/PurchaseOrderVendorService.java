package com.positivity.order.internal.service;

import com.positivity.order.internal.dto.purchaseorder.ListPurchaseOrderVendorsRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderVendorResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The purchase-order screens' read of pos-order's vendor copy (CAP:550, #2680): the vendors a purchase order may
 * name, from {@code ext_supplier_vendor} alone. pos-order never calls pos-supplier (ADR-0044 R1).
 */
public interface PurchaseOrderVendorService {

    /**
     * Pages the caller's tenant's copy rows matching the filter. Sorting is by {@code vendorNumber}, {@code
     * displayName} or {@code status} only, with {@code vendorId} as the tie-breaker.
     *
     * @throws com.positivity.order.internal.exception.PurchaseOrderRequestValidationException on any other sort
     *     property
     */
    @NonNull Page<PurchaseOrderVendorResponse> listVendors(
            @NonNull ListPurchaseOrderVendorsRequest filter, @NonNull Pageable pageable);

    /**
     * One copy row, whatever its status: an INACTIVE vendor is a fact to show, not a refusal.
     *
     * @throws com.positivity.web.common.ReplicationPendingException {@code VENDOR_REPLICATION_PENDING} when the copy
     *     does not hold the id: it may only not have replicated yet, never a 404 (ADR-0017 §1)
     */
    @NonNull PurchaseOrderVendorResponse getVendor(@NonNull UUID vendorId);
}
