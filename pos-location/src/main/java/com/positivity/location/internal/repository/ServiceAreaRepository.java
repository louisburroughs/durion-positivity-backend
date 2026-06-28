package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.ServiceAreaEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for service areas.
 *
 * Issue: #76
 */
public interface ServiceAreaRepository extends JpaRepository<ServiceAreaEntity, UUID> {

    Optional<ServiceAreaEntity> findByNameIgnoreCase(String name);
}
