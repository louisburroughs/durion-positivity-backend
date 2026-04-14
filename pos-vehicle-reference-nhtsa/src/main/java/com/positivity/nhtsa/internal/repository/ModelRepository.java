package com.positivity.nhtsa.internal.repository;

import com.positivity.nhtsa.internal.entity.Model;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelRepository extends JpaRepository<Model, UUID> {
    List<Model> findByMakeId(UUID makeId);
}
