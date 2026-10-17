package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierVendorCreateRequestEntity;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Idempotency keys of vendor creates (#2678); row-level security scopes them to the bound tenant. */
public interface SupplierVendorCreateRequestRepository extends JpaRepository<SupplierVendorCreateRequestEntity, UUID> {

    /** The row a client key answered, if any; row-level security keeps it to the bound tenant. */
    Optional<SupplierVendorCreateRequestEntity> findByRequestId(@NonNull UUID requestId);
}
