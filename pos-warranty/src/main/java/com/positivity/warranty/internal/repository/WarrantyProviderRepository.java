package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.WarrantyProvider;
import com.positivity.warranty.internal.enums.ProviderType;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarrantyProviderRepository extends JpaRepository<WarrantyProvider, UUID> {

    @NonNull
    List<WarrantyProvider> findByProviderType(@NonNull ProviderType providerType);

    /** Policy lookup by product manufacturer (PRD §3.1 manufacturerId). */
    @NonNull
    List<WarrantyProvider> findByManufacturerId(@NonNull UUID manufacturerId);

    /** Accounting credit matching (PRD §3.1 apVendorId). */
    @NonNull
    List<WarrantyProvider> findByApVendorId(@NonNull UUID apVendorId);

    @NonNull
    List<WarrantyProvider> findByStatus(@NonNull String status);
}
