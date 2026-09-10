package com.positivity.poseventreceiver.internal.repository;

import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface EmittedEventRepository extends Repository<EmittedEvent, UUID> {

    // Deliberately not a JpaRepository: emitted_event has no row-level security (ADR-0062
    // exception), so an inherited findAll()/findById() would read across tenants. Only
    // tenant-bound reads and the writes the module needs are declared here.

    <S extends EmittedEvent> List<S> saveAll(Iterable<S> events);

    <S extends EmittedEvent> List<S> saveAllAndFlush(Iterable<S> events);

    /**
     * Event counts by type for one tenant. {@code emitted_event} has no row-level security (see
     * {@link EmittedEvent}), so the tenant predicate here is the isolation.
     */
    @Query("SELECT e.id, COUNT(e) FROM EmittedEvent e WHERE e.tenantId = :tenantId AND e.publishedAt >= :since"
            + " GROUP BY e.id ORDER BY COUNT(e) DESC")
    List<Object[]> countByEventTypeIdSince(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    /**
     * Entity-indexed event lookup for GET /v1/events (issue #1521). Always bound on
     * publishedAt — the hypertable's partition column — so this never becomes an unbounded
     * scan. Bound on the tenant as well, since {@code emitted_event} has no row-level security (see
     * {@link EmittedEvent}); the partial index {@code idx_emitted_event_tenant_entity_time}
     * ({@code tenant_id, entity_id, published_at DESC}, V1_1) serves exactly this shape.
     */
    Page<EmittedEvent> findByTenantIdAndEntityIdAndPublishedAtGreaterThanEqual(
            UUID tenantId, String entityId, Instant since, Pageable pageable);
}
