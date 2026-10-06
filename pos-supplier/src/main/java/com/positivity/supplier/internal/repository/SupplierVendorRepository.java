package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierVendorEntity;
import com.positivity.supplier.internal.enums.VendorStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The vendor master (#2516). Every query is the bound tenant's (row-level security, ADR-0062). */
public interface SupplierVendorRepository extends JpaRepository<SupplierVendorEntity, UUID> {

    /** The tenant's vendor with that number; numbers are unique per tenant. */
    @NonNull
    Optional<SupplierVendorEntity> findByVendorNumber(@NonNull String vendorNumber);

    boolean existsByVendorNumber(@NonNull String vendorNumber);

    /**
     * The vendor list: optionally narrowed to one status and to vendors whose number, display name or
     * legal name contains {@code pattern} (already lower-cased and wrapped in {@code %}). Ordered by
     * vendor number, so the list reads the way people quote vendors.
     */
    @Query("select v from SupplierVendorEntity v"
            + " where (:status is null or v.status = :status)"
            + " and (:pattern is null"
            + " or lower(v.vendorNumber) like :pattern"
            + " or lower(v.displayName) like :pattern"
            + " or lower(v.legalName) like :pattern)"
            + " order by v.vendorNumber asc")
    @NonNull
    Page<SupplierVendorEntity> search(
            @Param("pattern") @Nullable String pattern,
            @Param("status") @Nullable VendorStatus status,
            @NonNull Pageable pageable);

    /** One replay page: the tenant's vendors after {@code afterVendorId} in id order. */
    @NonNull
    List<SupplierVendorEntity> findByVendorIdGreaterThanOrderByVendorIdAsc(
            @NonNull UUID afterVendorId, @NonNull Pageable page);

    /** The first replay page. */
    @NonNull
    List<SupplierVendorEntity> findAllByOrderByVendorIdAsc(@NonNull Pageable page);
}
