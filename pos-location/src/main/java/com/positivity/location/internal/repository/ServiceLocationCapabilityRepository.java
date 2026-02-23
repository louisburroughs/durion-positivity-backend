package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.ServiceLocationCapabilityEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ServiceLocationCapabilityRepository extends JpaRepository<ServiceLocationCapabilityEntity, UUID> {

    List<ServiceLocationCapabilityEntity> findByCodeIn(Collection<String> codes);
}
