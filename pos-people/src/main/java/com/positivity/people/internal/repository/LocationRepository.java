package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LocationRepository extends JpaRepository<Location, UUID> {

    Optional<Location> findByCode(String code);

    Optional<Location> findByLocationIdAndActiveTrue(UUID locationId);

    List<Location> findAllByActiveTrue();
}
