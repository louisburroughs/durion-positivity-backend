package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.BreakSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link BreakSegment} entities.
 * Implements CAP-139 Story #68.
 */
@Repository
public interface BreakSegmentRepository extends JpaRepository<BreakSegment, UUID> {

    /**
     * Find all break segments belonging to the given work session.
     *
     * @param workSessionId parent session UUID
     * @return list of break segments
     */
    List<BreakSegment> findByWorkSessionId(UUID workSessionId);

    /**
     * Find all open (not yet stopped) break segments for a work session.
     *
     * @param workSessionId parent session UUID
     * @return list of open break segments
     */
    List<BreakSegment> findByWorkSessionIdAndBreakEndAtIsNull(UUID workSessionId);

    /**
     * Find the first open (not yet stopped) break segment for a work session.
     *
     * @param workSessionId parent session UUID
     * @return Optional containing the open break segment, or empty if none
     */
    Optional<BreakSegment> findFirstByWorkSessionIdAndBreakEndAtIsNull(UUID workSessionId);
}
