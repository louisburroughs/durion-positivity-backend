package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtSupplierVendorReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** pos-supplier's vendors as pos-inventory holds them (CAP:550 S36, #2525). Written only by the vendor-fact consumer. */
public interface ExtSupplierVendorReplicaRepository extends JpaRepository<ExtSupplierVendorReplica, UUID> {

    List<ExtSupplierVendorReplica> findAllByVendorIdIn(Collection<UUID> vendorIds);
}
