package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.ServiceAreaEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for service areas.
 *
 * Issue: #76
 */
@Repository
public interface ServiceAreaRepository extends JpaRepository<ServiceAreaEntity, UUID> {

    Optional<ServiceAreaEntity> findByNameIgnoreCase(String name);
}
