package com.positivity.order.internal.service;

import com.positivity.order.internal.dto.CashMovementSummary;
import com.positivity.order.internal.dto.RegisterSessionSummary;
import com.positivity.order.internal.dto.SessionReport;
import com.positivity.order.internal.service.model.CashMovementCommand;
import com.positivity.order.internal.service.model.CashMovementOptions;
import com.positivity.order.internal.service.model.CashMovementResult;
import com.positivity.order.internal.service.model.OpenSessionCommand;
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
     * (spec R6.1). The opening float is the register's configured float from accounting, zero when none
     * (CAP:550 S16, AW16); the opener is the caller (ADR-0018).
     */
    @NonNull
    RegisterSessionSummary openSession(@NonNull OpenSessionCommand command);

    /** The current OPEN (or CLOSING) session on a terminal, if any. */
    @NonNull
    Optional<RegisterSessionSummary> currentSessionForTerminal(@NonNull String terminalId);

    /** Fetches a session by id (404 if unknown). */
    @NonNull
    RegisterSessionSummary getSession(@NonNull UUID sessionId);

    /**
     * Records a drawer movement with one of the fixed reasons against an OPEN session (spec R6.6;
     * CAP:550 S16, AW15, AW19, AW31): reason fields, the drawer policy, the running-total limit, a
     * manager's approval and the float match. Idempotent on the command's {@code requestId}.
     */
    @NonNull
    CashMovementResult recordCashMovement(@NonNull CashMovementCommand command);

    /** What the register may offer the cashier for an OPEN or CLOSING session (CAP:550 S16, spec §6.2). */
    @NonNull
    CashMovementOptions cashMovementOptions(@NonNull UUID sessionId);

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
     * the session to CLOSED. An over/short beyond the tenant's over/short tolerance requires the
     * {@code order:session:approve_variance} authority; emits {@code order.session.closed} (schema 2,
     * with every movement).
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
