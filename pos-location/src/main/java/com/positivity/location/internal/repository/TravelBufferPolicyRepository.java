package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.TravelBufferPolicyEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for travel buffer policies.
 *
 * Issue: #76
 */
@Repository
public interface TravelBufferPolicyRepository extends JpaRepository<TravelBufferPolicyEntity, UUID> {

    Optional<TravelBufferPolicyEntity> findByNameIgnoreCase(String name);
}
