package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtSupplierVendorReplica;
import com.positivity.inventory.internal.repository.ExtSupplierVendorReplicaRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Reads pos-inventory's copy of the pos-supplier vendor master (CAP:550 S36, #2525). The single place the rest of the
 * module reaches {@code ext_supplier_vendor}, so a reader never sees the repository or writes to the copy.
 */
@Component
@RequiredArgsConstructor
public class VendorCopyReader {

    private final ExtSupplierVendorReplicaRepository vendors;

    /** The vendors held for the given ids, keyed by vendor id; an id with no row is absent from the map. */
    public @NonNull Map<UUID, ExtSupplierVendorReplica> byIds(@NonNull Collection<UUID> vendorIds) {
        Map<UUID, ExtSupplierVendorReplica> found = new HashMap<>();
        if (vendorIds.isEmpty()) {
            return found;
        }
        for (ExtSupplierVendorReplica vendor : vendors.findAllByVendorIdIn(vendorIds)) {
            found.put(vendor.getVendorId(), vendor);
        }
        return found;
    }

    /** The vendor held for the id, any status; empty when the copy has no such vendor. */
    public @NonNull Optional<ExtSupplierVendorReplica> find(@NonNull UUID vendorId) {
        return vendors.findById(vendorId);
    }
}
