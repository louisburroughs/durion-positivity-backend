package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.Location;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    boolean existsById(UUID id);

    boolean existsByNormalizedName(String normalizedName);

    Page<Location> findByStatus(String status, Pageable pageable);

    Page<Location> findByUpdatedAtAfter(Instant since, Pageable pageable);

    Page<Location> findByStatusAndUpdatedAtAfter(String status, Instant since, Pageable pageable);

    Optional<Location> findByNormalizedNameAndIdNot(String normalizedName, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Location l WHERE l.id = :id")
    Optional<Location> findByIdForUpdate(UUID id);

    /**
     * Active locations that sit at the top of the parent-child hierarchy: they are a
     * parent of at least one edge but a child of none. Ordered by id (UUID v7 is
     * time-ordered) so the first row is a stable, deterministic pick.
     *
     * Issue: #1636
     */
    @Query("""
            SELECT l FROM Location l
            WHERE l.active = true
              AND EXISTS (SELECT 1 FROM LocationParent lp WHERE lp.parent = l)
              AND NOT EXISTS (SELECT 1 FROM LocationParent lc WHERE lc.child = l)
            ORDER BY l.id ASC
            """)
    List<Location> findActiveHierarchyRoots();

    Optional<Location> findFirstByActiveTrueOrderByIdAsc();
}
