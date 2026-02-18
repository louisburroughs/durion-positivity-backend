package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.GeographicalLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface GeographicalLocationRepository extends JpaRepository<GeographicalLocation, UUID> {
}
