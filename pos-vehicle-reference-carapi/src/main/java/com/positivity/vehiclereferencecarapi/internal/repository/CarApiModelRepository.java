package com.positivity.vehiclereferencecarapi.internal.repository;

import com.positivity.vehiclereferencecarapi.internal.entity.CarApiModel;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarApiModelRepository extends JpaRepository<CarApiModel, UUID> {
    List<CarApiModel> findByMakeId(UUID makeId);
}
