package com.positivity.nhtsa.internal.repository;

import com.positivity.nhtsa.internal.entity.Make;
import com.positivity.tenancy.TenantAudited;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MakeRepository extends JpaRepository<Make, UUID> {
    /** Every make linked to the manufacturer through {@code make_manufacturer}. */
    @NonNull
    List<Make> findByManufacturersId(@NonNull UUID manufacturerId);

    /** The make carrying this vPIC Make_ID; at most one (unique index {@code ux_make_nhtsa_id}). */
    @NonNull
    Optional<Make> findByNhtsaId(@NonNull Long nhtsaId);

    /**
     * Inserts the make unless a unique index already holds it. {@code ON CONFLICT DO NOTHING} keeps a lost race
     * from raising, which would mark the caller's transaction rollback-only (#2471). Flushes before and clears the
     * persistence context after, so the caller re-reads the row.
     */
    @TenantAudited(
            reason = "make and make_manufacturer are global reference tables (db/tenancy-global-tables.txt):"
                    + " no tenant_id, no row-level security, so there is no tenant scope to apply")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value = "INSERT INTO make (id, nhtsa_id, name, cache_timestamp)"
                    + " VALUES (:id, :nhtsaId, :name, :cachedAt) ON CONFLICT DO NOTHING")
    int insertIgnoringConflict(
            @Param("id") @NonNull UUID id,
            @Param("nhtsaId") @NonNull Long nhtsaId,
            @Param("name") @NonNull String name,
            @Param("cachedAt") @NonNull LocalDateTime cachedAt);

    /** Links the make to the manufacturer unless the pair already exists; see {@link #insertIgnoringConflict}. */
    @TenantAudited(
            reason = "make and make_manufacturer are global reference tables (db/tenancy-global-tables.txt):"
                    + " no tenant_id, no row-level security, so there is no tenant scope to apply")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value = "INSERT INTO make_manufacturer (make_id, manufacturer_id) VALUES (:makeId, :manufacturerId)"
                    + " ON CONFLICT DO NOTHING")
    int insertLinkIgnoringConflict(
            @Param("makeId") @NonNull UUID makeId, @Param("manufacturerId") @NonNull UUID manufacturerId);
}
