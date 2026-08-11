package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierAuthConfigRepository extends JpaRepository<SupplierAuthConfigEntity, UUID> {

    @NonNull
    List<SupplierAuthConfigEntity> findByVendorProfileIdOrderByNameAsc(@NonNull UUID vendorProfileId);

    /** Name lookup within a profile — {@code name} is unique per profile (ADR-0050 §4). */
    @NonNull
    Optional<SupplierAuthConfigEntity> findByVendorProfileIdAndName(@NonNull UUID vendorProfileId, @NonNull String name);

    /** Child lookup scoped to its owning profile (a foreign profile's child is not-found). */
    @NonNull
    Optional<SupplierAuthConfigEntity> findByIdAndVendorProfileId(@NonNull UUID id, @NonNull UUID vendorProfileId);

    void deleteByVendorProfileId(@NonNull UUID vendorProfileId);
}
