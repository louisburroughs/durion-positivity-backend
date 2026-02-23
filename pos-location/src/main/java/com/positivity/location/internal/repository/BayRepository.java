package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.BayEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for bay persistence operations.
 *
 * Issue: CAP-136 #77
 */
@Repository
public interface BayRepository extends JpaRepository<BayEntity, UUID> {

    Optional<BayEntity> findById(UUID id);

    <S extends BayEntity> S save(S entity);

    Optional<BayEntity> findByLocationIdAndNormalizedName(UUID locationId, String normalizedName);

    Page<BayEntity> findByLocationId(UUID locationId, Pageable pageable);

    Page<BayEntity> findByLocationIdAndStatus(UUID locationId, String status, Pageable pageable);

    Page<BayEntity> findByLocationIdAndBayType(UUID locationId, String bayType, Pageable pageable);

    Page<BayEntity> findByLocationIdAndStatusAndBayType(UUID locationId, String status, String bayType,
            Pageable pageable);

    Optional<BayEntity> findByIdAndLocationId(UUID id, UUID locationId);

    boolean existsByLocationIdAndNameIgnoreCase(UUID locationId, String name);
}
