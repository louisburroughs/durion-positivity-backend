package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ProcessedEvent;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /**
     * Event ids of one owning domain received under one tenant in a reconciliation window (ADR-0044 §4). UUIDv7
     * strings sort lexicographically by their embedded timestamp, so the bounds are the window
     * edges rendered via {@code UuidV7Timestamps.minStringAt} ({@code lowerBound} inclusive,
     * {@code upperBound} exclusive).
     */
    @Query("select p.eventId from ProcessedEvent p where p.owner = :owner and p.tenantId = :tenantId"
            + " and p.eventId >= :lowerBound and p.eventId < :upperBound")
    @NonNull
    List<String> findEventIdsInRange(
            @Param("owner") @NonNull String owner,
            @Param("tenantId") @NonNull UUID tenantId,
            @Param("lowerBound") @NonNull String lowerBound,
            @Param("upperBound") @NonNull String upperBound);
}
