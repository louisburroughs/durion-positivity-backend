package com.positivity.vehiclereferencecarapi.repository;

import com.positivity.vehiclereferencecarapi.entity.CarApiMake;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CarApiMakeRepository extends JpaRepository<CarApiMake, Long> {
    Optional<CarApiMake> findByMakeId(String makeId);
    Optional<CarApiMake> findByMakeName(String makeName);
}

