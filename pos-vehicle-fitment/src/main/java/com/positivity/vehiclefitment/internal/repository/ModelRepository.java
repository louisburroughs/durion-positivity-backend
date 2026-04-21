package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.Model;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelRepository extends JpaRepository<Model, UUID> {
    List<Model> findByMakeId(UUID makeId);

    Optional<Model> findByMakeIdAndNameIgnoreCase(UUID makeId, String name);

    List<Model> findAllByNameIgnoreCase(String name);

    Optional<Model> findByMakeIsNullAndNameIgnoreCase(String name);
}
