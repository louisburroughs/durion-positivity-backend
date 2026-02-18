package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.LocationGuardrailPolicyEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationGuardrailPolicyRepository extends JpaRepository<LocationGuardrailPolicyEntity, UUID> {

    Optional<LocationGuardrailPolicyEntity> findByLocationId(UUID locationId);
}
