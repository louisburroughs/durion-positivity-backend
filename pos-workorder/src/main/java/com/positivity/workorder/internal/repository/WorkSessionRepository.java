package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkSession;
import com.positivity.workorder.internal.enums.WorkSessionStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link WorkSession} entities.
 * Implements CAP-139 Story #68.
 */
@Repository
public interface WorkSessionRepository extends JpaRepository<WorkSession, UUID> {

    /**
     * Find all sessions for a given mechanic with the specified status.
     *
     * @param mechanicId mechanic UUID
     * @param status     session status to filter by
     * @return list of matching sessions
     */
    List<WorkSession> findByMechanicIdAndStatus(UUID mechanicId, WorkSessionStatus status);
}
