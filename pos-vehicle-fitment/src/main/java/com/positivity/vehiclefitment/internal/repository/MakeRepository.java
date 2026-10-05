package com.positivity.vehiclefitment.internal.repository;

import com.positivity.tenancy.TenantAudited;
import com.positivity.vehiclefitment.internal.entity.Make;
import java.time.Instant;
import java.util.List;
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

    /** The manufacturer's linked makes with this name; more than one only when a vPIC and a local row share it. */
    @NonNull
    List<Make> findByManufacturersIdAndNameIgnoreCase(@NonNull UUID manufacturerId, @NonNull String name);

    @NonNull
    List<Make> findAllByNameIgnoreCase(@NonNull String name);

    /**
     * Inserts the make unless a unique index already holds it. {@code ON CONFLICT DO NOTHING} keeps a lost race
     * from raising, which would mark the caller's transaction rollback-only (#2453). Flushes before and clears
     * the persistence context after, so the caller re-reads the row.
     */
    @TenantAudited(
            reason = "make and make_manufacturer are global reference tables (db/tenancy-global-tables.txt):"
                    + " no tenant_id, no row-level security, so there is no tenant scope to apply")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value = "INSERT INTO make (id, name, created_at, updated_at) VALUES (:id, :name, :now, :now)"
                    + " ON CONFLICT DO NOTHING")
    int insertIgnoringConflict(
            @Param("id") @NonNull UUID id, @Param("name") @NonNull String name, @Param("now") @NonNull Instant now);

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
