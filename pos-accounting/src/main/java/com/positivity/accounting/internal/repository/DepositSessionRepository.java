package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.DepositSession;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** What each session contributed to a deposit (CAP:550 S18, #2514). */
public interface DepositSessionRepository extends JpaRepository<DepositSession, UUID> {

    /** A deposit's sessions, oldest close first. */
    @NonNull
    List<DepositSession> findByDepositIdOrderByClosedAtAscSessionIdAsc(@NonNull UUID depositId);
}
