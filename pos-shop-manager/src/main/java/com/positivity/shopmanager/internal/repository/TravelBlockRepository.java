package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.TravelBlock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelBlockRepository extends JpaRepository<TravelBlock, UUID> {
    @Query(
            "SELECT t FROM TravelBlock t WHERE t.personId = :personId AND t.startTime < :windowEnd AND t.endTime > :windowStart")
    List<TravelBlock> findOverlapping(
            @Param("personId") String personId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);
}
