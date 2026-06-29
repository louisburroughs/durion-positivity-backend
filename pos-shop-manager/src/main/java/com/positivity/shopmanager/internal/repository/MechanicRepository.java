package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MechanicRepository extends JpaRepository<Mechanic, UUID> {
    Optional<Mechanic> findByPersonId(String personId);

    List<Mechanic> findAllByStatus(MechanicStatus status);
}
