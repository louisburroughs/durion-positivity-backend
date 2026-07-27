package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtVehicle;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtVehicleRepository extends JpaRepository<ExtVehicle, UUID> {}
