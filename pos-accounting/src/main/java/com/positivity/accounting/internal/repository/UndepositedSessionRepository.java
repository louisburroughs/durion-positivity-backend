package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The undeposited-sessions read model (CAP:550 S18, #2514). */
public interface UndepositedSessionRepository extends JpaRepository<UndepositedSession, UUID> {

    /** Whether the session's close fact already wrote its row (a redelivery writes nothing). */
    boolean existsBySessionId(@NonNull UUID sessionId);

    @NonNull
    Optional<UndepositedSession> findBySessionId(@NonNull UUID sessionId);

    /** The rows of the given sessions, in no particular order. */
    @NonNull
    List<UndepositedSession> findBySessionIdIn(@NonNull Collection<UUID> sessionIds);

    /** The sessions in one status, oldest close first. */
    @NonNull
    List<UndepositedSession> findByStatusOrderByClosedAtAscSessionIdAsc(@NonNull UndepositedSessionStatus status);

    /**
     * The named sessions, row-locked to the end of the transaction in session-id order, so two deposits naming the
     * same sessions serialize instead of deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UndepositedSession s WHERE s.sessionId IN :sessionIds ORDER BY s.sessionId")
    @NonNull
    List<UndepositedSession> lockBySessionIdIn(@Param("sessionIds") @NonNull Collection<UUID> sessionIds);

    /** The sessions a deposit took, row-locked in session-id order (its reversal returns them). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UndepositedSession s WHERE s.depositId = :depositId ORDER BY s.sessionId")
    @NonNull
    List<UndepositedSession> lockByDepositId(@Param("depositId") @NonNull UUID depositId);

    /** The bank drops of every session in {@code status}: the drawer cash not yet at the bank. */
    @Query("SELECT COALESCE(SUM(s.depositAmount), 0) FROM UndepositedSession s WHERE s.status = :status")
    @NonNull
    BigDecimal sumDepositAmountByStatus(@Param("status") @NonNull UndepositedSessionStatus status);

    /** The oldest session in {@code status}, by close time. */
    @NonNull
    Optional<UndepositedSession> findFirstByStatusOrderByClosedAtAsc(@NonNull UndepositedSessionStatus status);
}
