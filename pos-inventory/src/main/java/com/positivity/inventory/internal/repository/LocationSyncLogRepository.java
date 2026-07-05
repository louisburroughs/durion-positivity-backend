package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.LocationSyncLogEntity;
import com.positivity.inventory.internal.enums.LocationSyncLogScope;
import com.positivity.inventory.internal.enums.LocationSyncOutcome;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocationSyncLogRepository extends JpaRepository<LocationSyncLogEntity, UUID> {

    @Query("""
            SELECT l FROM LocationSyncLogEntity l
            WHERE (:outcome IS NULL OR l.outcome = :outcome)
            """)
    Page<LocationSyncLogEntity> findByOptionalOutcome(@Param("outcome") LocationSyncOutcome outcome, Pageable pageable);

    Optional<LocationSyncLogEntity> findFirstByIdempotencyKeyAndScope(
            String idempotencyKey, LocationSyncLogScope scope);
}
