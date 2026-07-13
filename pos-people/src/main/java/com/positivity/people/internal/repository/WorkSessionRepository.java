package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.WorkSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkSessionRepository extends JpaRepository<WorkSession, UUID> {

    Optional<WorkSession> findByPersonIdAndEndedAtIsNull(UUID personId);

    Optional<WorkSession> findBySessionIdAndEndedAtIsNull(UUID sessionId);
}
