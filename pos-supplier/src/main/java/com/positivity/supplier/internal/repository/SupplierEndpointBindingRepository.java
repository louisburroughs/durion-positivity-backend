package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierEndpointBindingRepository extends JpaRepository<SupplierEndpointBindingEntity, UUID> {

    @NonNull
    List<SupplierEndpointBindingEntity> findByVendorProfileIdOrderByCapabilityAsc(@NonNull UUID vendorProfileId);

    /** Capability lookup — at most one binding per (profile, capability) (ADR-0050 §3). */
    @NonNull
    Optional<SupplierEndpointBindingEntity> findByVendorProfileIdAndCapability(
            @NonNull UUID vendorProfileId, @NonNull SupplierCapability capability);

    /**
     * Enabled bindings of one capability that carry a cron — the candidate set the scheduler polls
     * (ADR-0050 §3). Disabled bindings are excluded here rather than filtered later, so a disabled
     * binding cannot fire even if a stale lease still names it.
     */
    @NonNull
    List<SupplierEndpointBindingEntity> findByCapabilityAndEnabledTrueAndScheduleCronIsNotNull(
            @NonNull SupplierCapability capability);

    /** Child lookup scoped to its owning profile (a foreign profile's child is not-found). */
    @NonNull
    Optional<SupplierEndpointBindingEntity> findByIdAndVendorProfileId(@NonNull UUID id, @NonNull UUID vendorProfileId);

    /** Referential guard: an auth config may not be deleted/renamed while referenced. */
    boolean existsByVendorProfileIdAndAuthConfigName(@NonNull UUID vendorProfileId, @NonNull String authConfigName);

    void deleteByVendorProfileId(@NonNull UUID vendorProfileId);
}
