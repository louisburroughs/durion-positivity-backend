package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtLocation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtLocationRepository extends JpaRepository<ExtLocation, UUID> {}
