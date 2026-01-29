package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.model.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ServiceRepository extends JpaRepository<ServiceEntity, Long> { // Changed String to Long
    List<ServiceEntity> findByName(String name);
}
