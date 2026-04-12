package com.positivity.poseventreceiver.internal.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.positivity.poseventreceiver.internal.entity.EmittedEventHourly;
import com.positivity.poseventreceiver.internal.entity.EmittedEventHourlyId;

@Repository
public interface EmittedEventHourlyRepository extends JpaRepository<EmittedEventHourly, EmittedEventHourlyId> {

  @Query("""
      SELECT h.eventType, SUM(h.eventCount)
      FROM EmittedEventHourly h
      WHERE h.bucket >= :since
      GROUP BY h.eventType
      ORDER BY SUM(h.eventCount) DESC
      """)
  List<Object[]> summarizeSince(@Param("since") Instant since);
}
