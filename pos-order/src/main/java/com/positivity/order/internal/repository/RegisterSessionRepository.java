package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RegisterSessionRepository extends JpaRepository<RegisterSession, UUID> {

    /** The current open (or closing) session on a terminal, if any. */
    Optional<RegisterSession> findFirstByTerminalIdAndStatus(String terminalId, RegisterSessionStatus status);

    /**
     * Whether the terminal has an <em>active</em> session (OPEN or CLOSING). A CLOSING session
     * still owns the terminal — begin-close freezes it — so opening a new session or binding a new
     * order must be blocked while one exists (Copilot #1093 review).
     */
    boolean existsByTerminalIdAndStatusIn(String terminalId, Collection<RegisterSessionStatus> statuses);

    /**
     * Most recent session on a terminal by any status, newest first — used to carry the previous
     * counted close forward as the next opening float.
     */
    Optional<RegisterSession> findFirstByTerminalIdOrderByOpenedAtDesc(String terminalId);

    List<RegisterSession> findByTerminalIdOrderByOpenedAtDesc(String terminalId);

    /** The terminal's sessions in the given statuses (OPEN and CLOSING: the drawer that holds it). */
    List<RegisterSession> findByTerminalIdAndStatusIn(String terminalId, Collection<RegisterSessionStatus> statuses);

    /**
     * The session, row-locked for the rest of the transaction (CAP:550 S16, #2512): cash movements and
     * approvals of one session are serialised on it, so a running total per reason is computed over
     * every movement already committed and a single-use approval is used once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RegisterSession s where s.sessionId = :sessionId")
    Optional<RegisterSession> findByIdForUpdate(@Param("sessionId") UUID sessionId);

    /** Counts one failed manager approval on the drawer, in a transaction of its own (CAP:550 S16). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RegisterSession s set s.stepUpDenials = s.stepUpDenials + 1 where s.sessionId = :sessionId")
    int countStepUpDenial(@Param("sessionId") UUID sessionId);
}
