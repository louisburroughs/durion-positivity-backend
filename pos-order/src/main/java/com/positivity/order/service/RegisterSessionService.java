package com.positivity.order.service;

import com.positivity.order.service.model.CashMovementCommand;
import com.positivity.order.service.model.CashMovementSummary;
import com.positivity.order.service.model.OpenSessionCommand;
import com.positivity.order.service.model.RegisterSessionSummary;
import com.positivity.order.service.model.SessionReport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Register (drawer) sessions and cash management — the Odoo {@code pos.session} + cash-control
 * analog (parity stories G1/G2, spec R6.1–R6.6). A session is a drawer shift on one terminal:
 * orders tendered while it is OPEN bind to it, and close reconciles the counted drawer against the
 * theoretical cash.
 */
public interface RegisterSessionService {

    /**
     * Opens a session on a terminal. Fails with a 409 if the terminal already has an OPEN session
     * (spec R6.1). Opening float defaults to the terminal's previous counted close when not given.
     */
    @NonNull
    RegisterSessionSummary openSession(@NonNull OpenSessionCommand command);

    /** The current OPEN (or CLOSING) session on a terminal, if any. */
    @NonNull
    Optional<RegisterSessionSummary> currentSessionForTerminal(@NonNull String terminalId);

    /** Fetches a session by id (404 if unknown). */
    @NonNull
    RegisterSessionSummary getSession(@NonNull UUID sessionId);

    /** Records a PAID_IN / PAID_OUT drawer movement against an OPEN session (spec R6.6). */
    @NonNull
    CashMovementSummary recordCashMovement(@NonNull CashMovementCommand command);

    /** Lists the cash movements for a session, oldest first. */
    @NonNull
    List<CashMovementSummary> listCashMovements(@NonNull UUID sessionId);

    /**
     * Begin-close (spec R6.3): records the counted drawer cash and moves the session to CLOSING.
     * Blocked with a 409 while any of the session's orders sit in PENDING_PAYMENT.
     */
    @NonNull
    RegisterSessionSummary beginClose(@NonNull UUID sessionId, @NonNull BigDecimal countedCash);

    /**
     * Confirm-close (spec R6.3–R6.4): snapshots the theoretical cash, computes over/short, and moves
     * the session to CLOSED. An over/short beyond the authorized difference limit requires the
     * {@code order:session:approve_variance} authority; emits {@code order.session.closed}.
     */
    @NonNull
    RegisterSessionSummary confirmClose(@NonNull UUID sessionId);

    /** Mid-day X-report: figures for an OPEN session without closing it (spec R6.5). */
    @NonNull
    SessionReport xReport(@NonNull UUID sessionId);

    /** Z-report: close summary for a session (spec R6.5). */
    @NonNull
    SessionReport zReport(@NonNull UUID sessionId);
}
