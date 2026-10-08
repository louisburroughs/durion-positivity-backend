package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.ExtSupplierVendor;
import com.positivity.order.internal.exception.PurchaseOrderVendorException;
import com.positivity.order.internal.repository.ExtSupplierVendorRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The vendor guard of a purchase order (CAP:550 S24, #2517; SPEC-accounting-workspace §7.3, G15): only an
 * {@code ACTIVE} vendor of pos-order's vendor copy takes a new, approved, re-vendored or transmitted order.
 *
 * <p>Applied on REST create, approve, transmit and a revision that changes the vendor. An order requested on
 * {@code order.commands.v1} is placed in {@code DRAFT} whatever vendor it names (the requester may name a feed id, see
 * S36), and this guard stops it at approval until the buyer revises the vendor. Reads only the copy: pos-order never
 * calls pos-supplier (ADR-0044 R1).
 */
@Component
@RequiredArgsConstructor
public class SupplierVendorGuard {

    private final ExtSupplierVendorRepository vendors;

    /**
     * Returns the vendor when it is in the copy and active.
     *
     * @throws PurchaseOrderVendorException {@code VENDOR_NOT_FOUND} when it is not in the copy, {@code
     *     VENDOR_INACTIVE} when it is inactive
     */
    public @NonNull ExtSupplierVendor requireActive(@NonNull UUID vendorId) {
        ExtSupplierVendor vendor = vendors.findById(vendorId).orElseThrow(PurchaseOrderVendorException::notFound);
        if (!vendor.isActive()) {
            throw PurchaseOrderVendorException.inactive(vendor.getVendorNumber());
        }
        return vendor;
    }
}
