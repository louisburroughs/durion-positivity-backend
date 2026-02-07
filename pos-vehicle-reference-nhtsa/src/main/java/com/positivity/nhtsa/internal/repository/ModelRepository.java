package com.positivity.nhtsa.internal.repository;

import com.positivity.nhtsa.internal.entity.Model;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ModelRepository extends JpaRepository<Model, UUID> {
    List<Model> findByMakeId(UUID makeId);
}
