package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierVendorNumberSequenceEntity;
import com.positivity.tenancy.TenantAudited;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The per-tenant vendor-number counter (#2516). */
public interface SupplierVendorNumberSequenceRepository
        extends JpaRepository<SupplierVendorNumberSequenceEntity, UUID> {

    /**
     * The bound tenant's counter row under a pessimistic write lock ({@code SELECT ... FOR UPDATE}).
     * There is at most one per tenant; concurrent allocators wait here until the holder commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @NonNull
    Optional<SupplierVendorNumberSequenceEntity> findFirstByOrderByIdAsc();

    /**
     * Creates the tenant's counter row unless one exists. {@code ON CONFLICT DO NOTHING} keeps a
     * concurrent first use from throwing, so the caller's transaction stays usable (pos-workorder #2342).
     *
     * @return 1 if this call inserted the row, 0 if the tenant already had one
     */
    @TenantAudited(
            reason = "names the tenant explicitly (the caller's resolved tenant), so the row is the bound tenant's;"
                    + " the policy's WITH CHECK still refuses any other tenant")
    @Modifying
    @Query(value = """
                    INSERT INTO supplier_vendor_number_sequence (tenant_id, id, next_value, updated_at)
                    VALUES (:tenantId, :id, 1, :now)
                    ON CONFLICT DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("tenantId") @NonNull UUID tenantId,
            @Param("id") @NonNull UUID id,
            @Param("now") @NonNull Instant now);
}
