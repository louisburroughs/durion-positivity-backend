package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.model.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {
    List<ServiceEntity> findByName(String name);
}
