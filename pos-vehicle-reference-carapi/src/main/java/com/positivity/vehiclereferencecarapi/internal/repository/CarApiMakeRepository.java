package com.positivity.vehiclereferencecarapi.internal.repository;

import com.positivity.vehiclereferencecarapi.internal.entity.CarApiMake;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarApiMakeRepository extends JpaRepository<CarApiMake, UUID> {
    Optional<CarApiMake> findByMakeId(UUID makeId);

    Optional<CarApiMake> findByMakeName(String makeName);
}
