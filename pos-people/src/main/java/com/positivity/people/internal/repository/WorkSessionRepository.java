package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.WorkSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkSessionRepository extends JpaRepository<WorkSession, UUID> {

    Optional<WorkSession> findByPersonIdAndEndedAtIsNull(String personId);

    Optional<WorkSession> findBySessionIdAndEndedAtIsNull(UUID sessionId);
}
