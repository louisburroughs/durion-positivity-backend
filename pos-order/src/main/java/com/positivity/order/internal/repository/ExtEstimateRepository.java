package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtEstimate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtEstimateRepository extends JpaRepository<ExtEstimate, UUID> {}
