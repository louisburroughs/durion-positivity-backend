package com.positivity.vehiclereferencecarapi.internal.repository;

import com.positivity.vehiclereferencecarapi.internal.entity.CarApiModel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CarApiModelRepository extends JpaRepository<CarApiModel, Long> {
    List<CarApiModel> findByMakeId(String makeId);
}

