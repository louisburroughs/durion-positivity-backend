package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ServiceRequirementProfileEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Requirement-profile headers (CAP-329), keyed by service id. */
public interface ServiceRequirementProfileRepository extends JpaRepository<ServiceRequirementProfileEntity, UUID> {

    @NonNull
    List<ServiceRequirementProfileEntity> findAllByServiceIdIn(@NonNull Collection<UUID> serviceIds);
}
