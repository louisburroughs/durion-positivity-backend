package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.BayEntity;
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
}
