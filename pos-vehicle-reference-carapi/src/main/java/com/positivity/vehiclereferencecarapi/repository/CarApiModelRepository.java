package com.positivity.vehiclereferencecarapi.repository;

import com.positivity.vehiclereferencecarapi.entity.CarApiModel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CarApiModelRepository extends JpaRepository<CarApiModel, Long> {
    List<CarApiModel> findByMakeId(String makeId);
}

