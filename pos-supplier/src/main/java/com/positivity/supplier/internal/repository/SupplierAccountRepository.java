package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.enums.SupplierAccountRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierAccountRepository extends JpaRepository<SupplierAccountEntity, UUID> {

    /** Admin list contract: billing before delivery ({@code BILLING < DELIVERY} as strings). */
    @NonNull
    List<SupplierAccountEntity> findByVendorProfileIdOrderByRoleAscAccountNumberAsc(@NonNull UUID vendorProfileId);

    /** Role scan — at most one row for {@code BILLING} (ADR-0050 §5). */
    @NonNull
    List<SupplierAccountEntity> findByVendorProfileIdAndRole(
            @NonNull UUID vendorProfileId, @NonNull SupplierAccountRole role);

    /** Delivery mapping lookup: {@code pos-location UUID → account number} (ADR-0050 §5). */
    @NonNull
    Optional<SupplierAccountEntity> findByVendorProfileIdAndRoleAndDeliveryLocationId(
            @NonNull UUID vendorProfileId, @NonNull SupplierAccountRole role, @NonNull UUID deliveryLocationId);

    /** Child lookup scoped to its owning profile (a foreign profile's child is not-found). */
    @NonNull
    Optional<SupplierAccountEntity> findByIdAndVendorProfileId(@NonNull UUID id, @NonNull UUID vendorProfileId);

    void deleteByVendorProfileId(@NonNull UUID vendorProfileId);
}
