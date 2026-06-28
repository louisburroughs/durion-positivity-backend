package com.positivity.poseventreceiver.internal.repository;

import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmittedEventRepository extends JpaRepository<EmittedEvent, UUID> {

    @Query(
            "SELECT e.id, COUNT(e) FROM EmittedEvent e WHERE e.publishedAt >= :since GROUP BY e.id ORDER BY COUNT(e) DESC")
    List<Object[]> countByEventTypeIdSince(@Param("since") Instant since);
}
