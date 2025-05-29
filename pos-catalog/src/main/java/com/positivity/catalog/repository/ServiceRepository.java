package com.positivity.catalog.repository;

import com.positivity.catalog.model.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ServiceRepository extends JpaRepository<ServiceEntity, String> {
    List<ServiceEntity> findByName(String name);
}
