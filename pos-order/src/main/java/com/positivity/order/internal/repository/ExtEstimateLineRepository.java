package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtEstimateLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtEstimateLineRepository extends JpaRepository<ExtEstimateLine, UUID> {

    List<ExtEstimateLine> findByEstimateId(UUID estimateId);

    void deleteByEstimateId(UUID estimateId);
}
