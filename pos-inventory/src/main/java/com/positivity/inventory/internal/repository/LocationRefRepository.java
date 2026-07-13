package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.LocationRefEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRefRepository extends JpaRepository<LocationRefEntity, UUID> {

    Optional<LocationRefEntity> findByLocationId(UUID locationId);

    List<LocationRefEntity> findByLocationIdIn(Collection<UUID> locationIds);
}
