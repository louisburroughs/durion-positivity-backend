package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.LocationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LocationTypeRepository extends JpaRepository<LocationType, UUID> {
}
