package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ServiceEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {
    List<ServiceEntity> findByName(String name);

    List<ServiceEntity> findByNameContainingIgnoreCaseOrderByNameAsc(String name);

    /**
     * A page of services for fact replay (#1306), ordered by id so a cursor can resume where the
     * previous page stopped.
     *
     * <p>Cursor rather than offset paging, for the reason the product replay uses one: offsets
     * shift under concurrent writes, and a service created mid-replay would displace another out of
     * the window, leaving a replica short of exactly the fact the replay existed to deliver.
     */
    @Query("""
      SELECT s FROM ServiceEntity s
      WHERE (:afterId IS NULL OR s.id > :afterId)
        AND (:updatedSince IS NULL OR s.updatedAt >= :updatedSince)
      ORDER BY s.id ASC
      """)
    List<ServiceEntity> findForReplay(
            @Param("afterId") UUID afterId, @Param("updatedSince") Instant updatedSince, Pageable pageable);
}
