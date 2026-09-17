package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.WorkSessionBreak;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkSessionBreakRepository extends JpaRepository<WorkSessionBreak, UUID> {

    Optional<WorkSessionBreak> findBySession_SessionIdAndEndedAtIsNull(UUID sessionId);

    /** Every open break across the given sessions in one query (issue #2061, BR7). */
    List<WorkSessionBreak> findBySession_SessionIdInAndEndedAtIsNull(Collection<UUID> sessionIds);
}
