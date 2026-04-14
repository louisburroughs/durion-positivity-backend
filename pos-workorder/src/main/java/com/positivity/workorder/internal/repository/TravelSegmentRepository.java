package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.TravelSegment;
import com.positivity.workorder.internal.enums.TravelSegmentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelSegmentRepository extends JpaRepository<TravelSegment, UUID> {
    List<TravelSegment> findByMobileWorkAssignmentIdAndStatus(UUID mobileWorkAssignmentId, TravelSegmentStatus status);

    List<TravelSegment> findByMobileWorkAssignmentIdAndTechnicianId(UUID mobileWorkAssignmentId, UUID technicianId);

    Optional<TravelSegment> findByTravelSegmentIdAndTechnicianId(UUID travelSegmentId, UUID technicianId);

    long countByMobileWorkAssignmentIdAndStatus(UUID mobileWorkAssignmentId, TravelSegmentStatus status);
}
