package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.Location;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
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
}
