package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.ServiceLocationCapabilityEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceLocationCapabilityRepository extends JpaRepository<ServiceLocationCapabilityEntity, UUID> {

    List<ServiceLocationCapabilityEntity> findByCodeIn(Collection<String> codes);
}
