package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Staged MKCAT tread design variants (CAP-324 #1230). */
@Repository
public interface SupplierMktCatVariantRepository extends JpaRepository<SupplierMktCatVariantEntity, UUID> {

    @NonNull
    Optional<SupplierMktCatVariantEntity> findByVendorProfileIdAndVendorVariantId(
            @NonNull UUID vendorProfileId, @NonNull String vendorVariantId);

    /**
     * Variants with artwork still missing, least recently touched first.
     *
     * <p>The retry runner's working set. Ordering by last update spreads attention: ordering by age
     * would let one permanently broken image absorb every run.
     */
    @NonNull
    List<SupplierMktCatVariantEntity> findByHasUnresolvedImagesTrueOrderByUpdatedAtAsc(@NonNull Limit limit);

    /** Everything staged for a vendor, for admin review of what the catalogue last sent. */
    @NonNull
    List<SupplierMktCatVariantEntity> findByVendorProfileIdOrderByLastSeenAtDesc(
            @NonNull UUID vendorProfileId, @NonNull Limit limit);
}
