package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.BreakSegment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link BreakSegment} entities.
 * Implements CAP-139 Story #68.
 */
public interface BreakSegmentRepository extends JpaRepository<BreakSegment, UUID> {

    /**
     * Find all break segments belonging to the given work session.
     *
     * @param workSessionId parent session UUID
     * @return list of break segments
     */
    List<BreakSegment> findByWorkSession_WorkSessionId(UUID workSessionId);

    /**
     * Find all open (not yet stopped) break segments for a work session.
     *
     * @param workSessionId parent session UUID
     * @return list of open break segments
     */
    List<BreakSegment> findByWorkSession_WorkSessionIdAndBreakEndAtIsNull(UUID workSessionId);

    /**
     * Find the first open (not yet stopped) break segment for a work session.
     *
     * @param workSessionId parent session UUID
     * @return Optional containing the open break segment, or empty if none
     */
    Optional<BreakSegment> findFirstByWorkSession_WorkSessionIdAndBreakEndAtIsNull(UUID workSessionId);
}
