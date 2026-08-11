package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.Segment;
import com.positivity.customer.internal.enums.AudienceType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SegmentRepository extends JpaRepository<Segment, UUID> {

    Optional<Segment> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Segment> findAllByOrderByNameAsc();

    List<Segment> findByAudienceTypeOrderByNameAsc(AudienceType audienceType);
}
