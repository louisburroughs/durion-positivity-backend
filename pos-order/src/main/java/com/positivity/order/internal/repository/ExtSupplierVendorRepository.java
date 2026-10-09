package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtSupplierVendor;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * pos-order's vendor copy (CAP:550 S24, #2517). Reads run on the tenant-bound connection, where forced row-level
 * security (ADR-0062) hides every other tenant's vendors, so another tenant's vendor reads as missing.
 */
public interface ExtSupplierVendorRepository extends JpaRepository<ExtSupplierVendor, UUID> {}
