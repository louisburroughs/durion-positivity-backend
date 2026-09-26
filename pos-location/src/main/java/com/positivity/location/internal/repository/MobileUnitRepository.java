package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.MobileUnitEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for mobile units.
 *
 * Issue: #76
 */
public interface MobileUnitRepository extends JpaRepository<MobileUnitEntity, UUID> {

    @Query("""
            SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END
            FROM MobileUnitEntity m
            WHERE m.baseLocation.id = :baseLocationId
              AND UPPER(m.name) = UPPER(:name)
            """)
    boolean existsByBaseLocationIdAndNameIgnoreCase(UUID baseLocationId, String name);

    /**
     * The rename check: another unit at this base location already holds the name, ignoring case
     * (#2252). A fast, friendly refusal only; {@code uq_mobile_unit_base_location_lower_name} (V6)
     * is what holds under concurrent writes.
     */
    @Query("""
            SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END
            FROM MobileUnitEntity m
            WHERE m.baseLocation.id = :baseLocationId
              AND UPPER(m.name) = UPPER(:name)
              AND m.id <> :excludedId
            """)
    boolean existsByBaseLocationIdAndNameIgnoreCaseAndIdNot(UUID baseLocationId, String name, UUID excludedId);

    /** One base location's units (#2253). */
    Page<MobileUnitEntity> findByBaseLocation_Id(UUID baseLocationId, Pageable pageable);

    /** One base location's units in one status (#2253). */
    Page<MobileUnitEntity> findByBaseLocation_IdAndStatus(UUID baseLocationId, String status, Pageable pageable);

    /** Every unit in one status (#2253). */
    Page<MobileUnitEntity> findByStatus(String status, Pageable pageable);

    /**
     * Counts mobile units per base location for a single status, for a batch of
     * locations.
     *
     * <p>Sibling of {@link #existsByBaseLocationIdAndNameIgnoreCase(UUID, String)}:
     * one aggregate round trip for the whole batch rather than a query per
     * location. {@code status} is free text on the entity, so the match is an
     * exact allow-list equality — any value the caller does not ask for is
     * excluded rather than assumed operational.
     *
     * Issue: #1657
     *
     * @param locationIds base locations to aggregate over; must not be empty
     * @param status      the mobile unit status to count, matched exactly
     * @return one row per base location that has at least one unit in that status
     */
    @Query("""
            SELECT new com.positivity.location.internal.repository.LocationCapabilityCount(
                       m.baseLocation.id, COUNT(m))
            FROM MobileUnitEntity m
            WHERE m.baseLocation.id IN :locationIds
              AND m.status = :status
            GROUP BY m.baseLocation.id
            """)
    List<LocationCapabilityCount> countByBaseLocationIdInAndStatus(Collection<UUID> locationIds, String status);

    /**
     * One keyset page of mobile units for the fact backfill (issue #1668), ordered by id and locked.
     *
     * <p>See {@code BayRepository.findBackfillPage} for why this is keyset rather than offset, and
     * why the shared lock is required to stop a concurrent delete from leaving a resurrected replica
     * row behind.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT m FROM MobileUnitEntity m WHERE m.id > :afterId ORDER BY m.id ASC")
    List<MobileUnitEntity> findBackfillPage(@Param("afterId") UUID afterId, Pageable pageable);
}
