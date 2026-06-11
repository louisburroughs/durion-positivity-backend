package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.WorkSessionBreak;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkSessionBreakRepository extends JpaRepository<WorkSessionBreak, UUID> {

    Optional<WorkSessionBreak> findBySession_SessionIdAndEndedAtIsNull(UUID sessionId);
}
