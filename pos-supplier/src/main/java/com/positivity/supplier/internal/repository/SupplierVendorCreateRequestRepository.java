package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierVendorCreateRequestEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Idempotency keys of vendor creates (#2678); row-level security scopes them to the bound tenant. */
public interface SupplierVendorCreateRequestRepository extends JpaRepository<SupplierVendorCreateRequestEntity, UUID> {}
