package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.TravelSegmentAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TravelSegmentAdjustmentRepository extends JpaRepository<TravelSegmentAdjustment, UUID> {
    List<TravelSegmentAdjustment> findByTravelSegmentId(UUID travelSegmentId);
}
