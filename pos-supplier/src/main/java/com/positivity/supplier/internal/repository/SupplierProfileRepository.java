package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.ProfileSourceOfTruth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierProfileRepository extends JpaRepository<SupplierProfileEntity, UUID> {

    /** Alias lookup (ADR-0050 §1): {@code supplierRef} is unique. */
    @NonNull
    Optional<SupplierProfileEntity> findBySupplierRef(@NonNull String supplierRef);

    /** Admin list contract: profiles ordered by {@code supplierRef}. */
    @NonNull
    List<SupplierProfileEntity> findAllByOrderBySupplierRefAsc();

    /** YAML reconciliation scan (ADR-0050 §6). */
    @NonNull
    List<SupplierProfileEntity> findBySourceOfTruth(@NonNull ProfileSourceOfTruth sourceOfTruth);
}
