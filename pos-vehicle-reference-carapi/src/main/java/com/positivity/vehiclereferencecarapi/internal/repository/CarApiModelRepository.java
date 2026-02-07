package com.positivity.vehiclereferencecarapi.internal.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.vehiclereferencecarapi.internal.entity.CarApiModel;

public interface CarApiModelRepository extends JpaRepository<CarApiModel, UUID> {
    List<CarApiModel> findByMakeId(UUID makeId);
}
