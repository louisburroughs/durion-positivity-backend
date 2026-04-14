package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.TravelSegmentAdjustment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelSegmentAdjustmentRepository extends JpaRepository<TravelSegmentAdjustment, UUID> {
    List<TravelSegmentAdjustment> findByTravelSegment_TravelSegmentId(UUID travelSegmentId);
}
