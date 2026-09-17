package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.WorkSession;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkSessionRepository extends JpaRepository<WorkSession, UUID> {

    Optional<WorkSession> findByPersonIdAndEndedAtIsNull(UUID personId);

    Optional<WorkSession> findBySessionIdAndEndedAtIsNull(UUID sessionId);

    /**
     * Every open session of the given people in one query, most recently started first, so a
     * roster's clock state is resolved without a query per person (issue #2061, BR7).
     */
    List<WorkSession> findByPersonIdInAndEndedAtIsNullOrderByStartedAtDesc(Collection<UUID> personIds);
}
