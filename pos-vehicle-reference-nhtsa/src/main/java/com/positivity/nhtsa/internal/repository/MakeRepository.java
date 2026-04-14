package com.positivity.nhtsa.internal.repository;

import com.positivity.nhtsa.internal.entity.Make;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MakeRepository extends JpaRepository<Make, UUID> {
    List<Make> findByManufacturerId(UUID manufacturerId);
}
