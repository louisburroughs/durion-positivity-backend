package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.BayEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for bay persistence operations.
 *
 * Issue: CAP-136 #77
 */
public interface BayRepository extends JpaRepository<BayEntity, UUID> {

    Optional<BayEntity> findById(UUID id);

    <S extends BayEntity> S save(S entity);

    @Query("SELECT b FROM BayEntity b WHERE b.location.id = :locationId AND b.normalizedName = :normalizedName")
    Optional<BayEntity> findByLocationIdAndNormalizedName(UUID locationId, String normalizedName);

    @Query("SELECT b FROM BayEntity b WHERE b.location.id = :locationId")
    Page<BayEntity> findByLocationId(UUID locationId, Pageable pageable);

    @Query("SELECT b FROM BayEntity b WHERE b.location.id = :locationId AND b.status = :status")
    Page<BayEntity> findByLocationIdAndStatus(UUID locationId, String status, Pageable pageable);

    @Query("SELECT b FROM BayEntity b WHERE b.location.id = :locationId AND b.bayType = :bayType")
    Page<BayEntity> findByLocationIdAndBayType(UUID locationId, String bayType, Pageable pageable);

    @Query("""
            SELECT b
            FROM BayEntity b
            WHERE b.location.id = :locationId
              AND b.status = :status
              AND b.bayType = :bayType
            """)
    Page<BayEntity> findByLocationIdAndStatusAndBayType(
            UUID locationId, String status, String bayType, Pageable pageable);

    @Query("SELECT b FROM BayEntity b WHERE b.id = :id AND b.location.id = :locationId")
    Optional<BayEntity> findByIdAndLocationId(UUID id, UUID locationId);

    @Query("""
            SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END
            FROM BayEntity b
            WHERE b.location.id = :locationId
              AND UPPER(b.name) = UPPER(:name)
            """)
    boolean existsByLocationIdAndNameIgnoreCase(UUID locationId, String name);

    /**
     * Counts bays per location for a single status, for a batch of locations.
     *
     * <p>Sibling of {@link #existsByLocationIdAndNameIgnoreCase(UUID, String)}: one
     * aggregate round trip for the whole batch rather than a query per location.
     * The status match is an exact allow-list equality, so only the status the
     * caller asks for is counted.
     *
     * Issue: #1657
     *
     * @param locationIds locations to aggregate over; must not be empty
     * @param status      the bay status to count, matched exactly
     * @return one row per location that has at least one bay in that status
     */
    @Query("""
            SELECT new com.positivity.location.internal.repository.LocationCapabilityCount(
                       b.location.id, COUNT(b))
            FROM BayEntity b
            WHERE b.location.id IN :locationIds
              AND b.status = :status
            GROUP BY b.location.id
            """)
    List<LocationCapabilityCount> countByLocationIdInAndStatus(Collection<UUID> locationIds, String status);
}
