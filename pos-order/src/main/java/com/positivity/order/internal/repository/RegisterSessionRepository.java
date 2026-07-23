package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
