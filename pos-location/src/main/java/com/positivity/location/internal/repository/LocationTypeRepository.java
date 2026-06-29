package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.LocationType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationTypeRepository extends JpaRepository<LocationType, UUID> {
    boolean existsByNameIgnoreCase(String name);

    Optional<LocationType> findByNameIgnoreCase(String name);
}
