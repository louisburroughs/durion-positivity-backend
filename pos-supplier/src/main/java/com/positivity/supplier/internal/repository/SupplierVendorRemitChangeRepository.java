package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierVendorRemitChangeEntity;
import com.positivity.supplier.internal.enums.RemitChangeStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Remit-to change requests (#2516). */
public interface SupplierVendorRemitChangeRepository extends JpaRepository<SupplierVendorRemitChangeEntity, UUID> {

    /** Whether the vendor already has a change waiting for a decision (at most one may). */
    boolean existsByVendorIdAndStatus(@NonNull UUID vendorId, @NonNull RemitChangeStatus status);

    @NonNull
    Optional<SupplierVendorRemitChangeEntity> findByChangeIdAndVendorId(@NonNull UUID changeId, @NonNull UUID vendorId);

    /** A vendor's change history, newest request first. */
    @NonNull
    List<SupplierVendorRemitChangeEntity> findByVendorIdOrderByRequestedAtDesc(@NonNull UUID vendorId);

    /** A vendor's changes in one status, newest request first. */
    @NonNull
    List<SupplierVendorRemitChangeEntity> findByVendorIdAndStatusOrderByRequestedAtDesc(
            @NonNull UUID vendorId, @NonNull RemitChangeStatus status);
}
