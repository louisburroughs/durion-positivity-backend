package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.Vendor;
import com.positivity.tenancy.TenantAudited;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for the AP vendor directory (Issue #816).
 */
public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    /**
     * Case-insensitive name-contains search, ordered by name, for typeahead
     * queries. Pass an unsorted {@link Pageable} to cap the result size.
     */
    List<Vendor> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Pageable pageable);

    /**
     * List all vendors ordered by name (used when no search term is given).
     */
    List<Vendor> findAllByOrderByNameAsc(Pageable pageable);

    /**
     * Create the directory row for a vendor unless one exists, in the caller's transaction on the
     * caller's connection (#2501; the shape of {@code AccountingSequenceRepository.insertIfAbsent},
     * #2342). {@code ON CONFLICT DO NOTHING} keeps the insert from throwing: a concurrent insert of
     * the same vendor, or a row for the same {@code vendor_id} that belongs to another tenant (the
     * primary key is {@code vendor_id} alone and row-level security hides that row), leaves the
     * caller's transaction committable and never marks it rollback-only. The target-less form works
     * on Postgres and on H2 in PostgreSQL mode. Native SQL skips the entity callbacks, so every
     * column is passed, the audit timestamps from the caller's clock (ADR-0024).
     *
     * @param tenantId the caller's resolved tenant
     * @param vendorId the vendor id the upstream system assigned
     * @param name     the vendor's display name, trimmed, not blank
     * @param now      the caller's clock
     * @return 1 if this call inserted the row, 0 if a row with that id already existed
     */
    @TenantAudited(
            reason = "names the tenant explicitly (the caller's resolved tenant), so the row is the bound tenant's on"
                    + " Postgres and on the H2 slices alike; the policy's WITH CHECK still refuses any other tenant")
    @Modifying
    @Query(value = """
                    INSERT INTO ap_vendor (tenant_id, vendor_id, name, status, created_at, updated_at)
                    VALUES (:tenantId, :vendorId, :name, 'ACTIVE', :now, :now)
                    ON CONFLICT DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("tenantId") UUID tenantId,
            @Param("vendorId") UUID vendorId,
            @Param("name") String name,
            @Param("now") Instant now);
}
