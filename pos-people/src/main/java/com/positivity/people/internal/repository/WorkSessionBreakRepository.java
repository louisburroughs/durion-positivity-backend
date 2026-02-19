package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.WorkSessionBreak;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkSessionBreakRepository extends JpaRepository<WorkSessionBreak, Long> {

    Optional<WorkSessionBreak> findBySessionIdAndEndedAtIsNull(Long sessionId);
}
