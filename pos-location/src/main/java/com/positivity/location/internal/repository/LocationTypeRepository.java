package com.positivity.location.internal.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.location.internal.entity.LocationType;

@Repository
public interface LocationTypeRepository extends JpaRepository<LocationType, UUID> {
        boolean existsByNameIgnoreCase(String name);

        Optional<LocationType> findByNameIgnoreCase(String name);

}
