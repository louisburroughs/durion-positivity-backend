package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtOrderRegisterSession;
import com.positivity.accounting.internal.enums.RegisterSessionStatus;
import com.positivity.accounting.internal.repository.ExtOrderRegisterSessionRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionOpenedV1;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The replica of pos-order's register sessions (#2571, #2573; ADR-0044 R3), which the register float relocation
 * reads so a register never moves while a session is open on it. Written only by {@link OrderEventsListener}, in
 * the handler transaction that marks the fact processed.
 *
 * <p>Each row is version-guarded on the session's envelope version ({@link ReplicaVersionGuard}: equal versions
 * apply). A session never reopens: a closed fact for an unknown session inserts a CLOSED row, and an opened fact
 * never touches a CLOSED row, so the two facts may arrive in either order.
 *
 * <p>Accepted race: a session opened in pos-order whose fact has not arrived yet does not block a relocation.
 * There is no hold, retry or synchronous call (ADR-0044 R1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterSessionReplica {

    private final ExtOrderRegisterSessionRepository sessions;
    private final Clock clock;

    /** Applies {@code order.session.opened}; joins the caller's transaction. */
    @Transactional
    public void opened(@NonNull RegisterSessionOpenedV1 fact, long aggregateVersion) {
        ExtOrderRegisterSession row = sessions.findById(fact.sessionId()).orElse(null);
        if (row != null && row.getStatus() == RegisterSessionStatus.CLOSED) {
            log.debug("Session {} is already closed; its opened fact changes nothing", fact.sessionId());
            return;
        }
        if (row != null && ReplicaVersionGuard.isStale(row.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Skipping stale order.session.opened sessionId={} version={}", fact.sessionId(), aggregateVersion);
            return;
        }
        if (row == null) {
            row = new ExtOrderRegisterSession();
            row.setSessionId(fact.sessionId());
        }
        row.setTerminalId(fact.terminalId());
        row.setLocationId(fact.locationId());
        row.setStatus(RegisterSessionStatus.OPEN);
        row.setOpenedAt(fact.openedAt());
        row.setClosedAt(null);
        row.setAggregateVersion(aggregateVersion);
        row.setSyncedAt(Instant.now(clock));
        sessions.save(row);
    }

    /** Applies {@code order.session.closed}; joins the caller's transaction. */
    @Transactional
    public void closed(@NonNull RegisterSessionClosedV1 fact, long aggregateVersion) {
        ExtOrderRegisterSession row = sessions.findById(fact.sessionId()).orElse(null);
        if (row != null
                && row.getStatus() == RegisterSessionStatus.CLOSED
                && ReplicaVersionGuard.isStale(row.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Skipping stale order.session.closed sessionId={} version={}", fact.sessionId(), aggregateVersion);
            return;
        }
        if (row == null) {
            row = new ExtOrderRegisterSession();
            row.setSessionId(fact.sessionId());
        }
        // A close always closes an OPEN row: the session never reopens, whatever the versions say.
        row.setTerminalId(fact.terminalId());
        row.setLocationId(fact.locationId());
        row.setStatus(RegisterSessionStatus.CLOSED);
        row.setOpenedAt(fact.openedAt());
        row.setClosedAt(fact.closedAt());
        row.setAggregateVersion(Math.max(row.getAggregateVersion(), aggregateVersion));
        row.setSyncedAt(Instant.now(clock));
        sessions.save(row);
    }

    /** The terminal's latest-opened session when it is still open; empty otherwise. Joins the caller's transaction. */
    public @NonNull Optional<ExtOrderRegisterSession> openSessionOf(@NonNull String terminalId) {
        return sessions.findFirstByTerminalIdOrderByOpenedAtDescSessionIdDesc(terminalId)
                .filter(session -> session.getStatus() == RegisterSessionStatus.OPEN);
    }
}
