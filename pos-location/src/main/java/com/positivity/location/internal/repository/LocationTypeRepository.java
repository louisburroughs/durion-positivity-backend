package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.LocationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LocationTypeRepository extends JpaRepository<LocationType, UUID> {
    boolean existsByNameIgnoreCase(String name);

    @Query("""
            select new LocationType(lt.id, lt.name, lt.description)
            from LocationType lt
            """)
    List<LocationType> findAllProjected();

    @Query("""
            select new LocationType(lt.id, lt.name, lt.description)
            from LocationType lt
            where lt.id = :id
            """)
    Optional<LocationType> findProjectedById(UUID id);
}
