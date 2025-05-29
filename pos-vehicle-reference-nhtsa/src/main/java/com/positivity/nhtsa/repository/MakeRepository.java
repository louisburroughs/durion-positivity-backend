package com.positivity.nhtsa.repository;

import com.positivity.nhtsa.entity.Make;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MakeRepository extends JpaRepository<Make, Long> {
    List<Make> findByManufacturerId(Long manufacturerId);
}

