package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface LocationRepository extends JpaRepository<Location, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Location l WHERE l.id = :id")
    Optional<Location> findByIdForUpdate(UUID id);
}
