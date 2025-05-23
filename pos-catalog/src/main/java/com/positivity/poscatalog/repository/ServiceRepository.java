package com.positivity.poscatalog.repository;

import com.positivity.poscatalog.model.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ServiceRepository extends JpaRepository<ServiceEntity, String> {
    List<ServiceEntity> findByName(String name);
}
