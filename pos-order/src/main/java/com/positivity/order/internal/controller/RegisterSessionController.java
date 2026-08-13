package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.BeginCloseRequest;
import com.positivity.order.internal.dto.CashMovementRequest;
import com.positivity.order.internal.dto.CashMovementResponse;
import com.positivity.order.internal.dto.OpenSessionRequest;
import com.positivity.order.internal.dto.RegisterSessionResponse;
import com.positivity.order.internal.dto.SessionReportResponse;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.service.RegisterSessionService;
import com.positivity.order.service.model.CashMovementCommand;
import com.positivity.order.service.model.OpenSessionCommand;
import com.positivity.order.service.model.RegisterSessionSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Register (drawer) sessions & cash management endpoints (parity stories G1/G2,
 * spec R6.1–R6.6):
 * open a session, record cash movements, run mid-day/close reports, and
 * reconcile the drawer at
 * close.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/orders/sessions")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
@Tag(name = "Register Sessions", description = "POS register session and cash management")
public class RegisterSessionController {

    private final RegisterSessionService registerSessionService;

    @Operation(
            operationId = "openRegisterSession",
            summary = "Open a Register Session",
            description = """
                    Opens an OPEN register (drawer) session on a terminal; sales orders created on the terminal \
                    while it is open bind to it, and it supplies their location by default.
                    Use this tool at the start of a drawer shift; do not use recordCashMovement, which requires a \
                    session that is already open.
                    Preconditions: the terminal must have no session in OPEN or CLOSING — one drawer per terminal.
                    Required inputs: terminalId and openedByClerkId; openingFloat defaults to the terminal's \
                    previous counted close (else zero) when omitted, and locationId defaults from the terminal's \
                    previous session.
                    Emits an ORDER_SESSION_OPEN event.
                    Returns 201 with the new session, and 409 when the terminal already has an active session.
                    """,
            tags = {"Register Sessions"})
    @PostMapping
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_OPEN + "')")
    @EmitEvent(id = "ORDER_SESSION_OPEN", apiVersion = "1")
    public ResponseEntity<RegisterSessionResponse> openSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The terminal, clerk, and optional opening-float context for the shift.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Morning open", value = """
                                                                    {"terminalId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60",
                                                                     "openedByClerkId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a50",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a90",
                                                                     "openingFloat":150.00}
                                                                    """)))
                    @Valid
                    @RequestBody
                    OpenSessionRequest request) {
        RegisterSessionSummary summary = registerSessionService.openSession(new OpenSessionCommand(
                request.getTerminalId(),
                request.getLocationId(),
                request.getOpeningFloat(),
                request.getOpenedByClerkId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(RegisterSessionResponse.from(summary));
    }

    @Operation(
            operationId = "getRegisterSession",
            summary = "Get a Register Session",
            description = """
                    Returns a register session with its status, opening float, counted and theoretical cash, \
                    over/short, and lifecycle timestamps.
                    Use this tool when the session id is already known; use getCurrentRegisterSession instead to \
                    resolve the active session from a terminal id.
                    Preconditions: the session must exist.
                    Required inputs: sessionId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no register session exists for the supplied id.
                    """,
            tags = {"Register Sessions"})
    @GetMapping("/{sessionId}")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_VIEW + "')")
    public ResponseEntity<RegisterSessionResponse> getSession(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(RegisterSessionResponse.from(registerSessionService.getSession(sessionId)));
    }

    @Operation(
            operationId = "getCurrentRegisterSession",
            summary = "Get the Current Session for a Terminal",
            description = """
                    Returns the active register session on a terminal, preferring an OPEN session and falling back \
                    to one in CLOSING.
                    Use this tool to resolve which drawer a terminal is on; use getRegisterSession instead when \
                    the session id is already known.
                    Preconditions: none — a terminal without an active session is a normal outcome.
                    Required inputs: terminalId as a query parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the OPEN or CLOSING session, and 204 when the terminal has no active session.
                    """,
            tags = {"Register Sessions"})
    @GetMapping("/current")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_VIEW + "')")
    public ResponseEntity<RegisterSessionResponse> currentSession(@RequestParam String terminalId) {
        return registerSessionService
                .currentSessionForTerminal(terminalId)
                .map(RegisterSessionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(
            operationId = "recordCashMovement",
            summary = "Record a Drawer Cash Movement",
            description = """
                    Records a PAID_IN or PAID_OUT cash movement against an OPEN register session; movements feed \
                    the theoretical-cash calculation at close.
                    Use this tool for non-sale drawer cash such as petty cash or bank drops; do not use \
                    beginSessionClose, which records the final counted drawer instead.
                    Preconditions: the session must exist and be OPEN — movements are rejected once closing has \
                    begun.
                    Required inputs: movementType (PAID_IN or PAID_OUT), a positive amount, reason, and clerkId.
                    Emits an ORDER_SESSION_CASH_MOVEMENT event.
                    Returns 201 with the recorded movement, 404 when the session does not exist, 409 when the \
                    session is not OPEN, and 422 when the amount is not positive or the movement type is unknown.
                    """,
            tags = {"Register Sessions"})
    @PostMapping("/{sessionId}/cash-movements")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_CASH_MOVEMENT + "')")
    @EmitEvent(id = "ORDER_SESSION_CASH_MOVEMENT", apiVersion = "1")
    public ResponseEntity<CashMovementResponse> recordCashMovement(
            @PathVariable UUID sessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The cash movement: direction, positive amount, reason, and clerk.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Petty cash out", value = """
                                                                    {"movementType":"PAID_OUT",
                                                                     "amount":40.00,
                                                                     "reason":"petty cash to office",
                                                                     "clerkId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a50"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CashMovementRequest request) {
        CashMovementResponse response =
                CashMovementResponse.from(registerSessionService.recordCashMovement(new CashMovementCommand(
                        sessionId,
                        request.getMovementType(),
                        request.getAmount(),
                        request.getReason(),
                        request.getClerkId())));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            operationId = "listCashMovements",
            summary = "List a Session's Cash Movements",
            description = """
                    Lists every recorded cash movement for a register session in the order they occurred.
                    Use this tool to review drawer ins and outs; use getSessionXReport instead for the aggregated \
                    mid-day figures that include tender totals and theoretical cash.
                    Preconditions: the session must exist.
                    Required inputs: sessionId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with a possibly empty list, and 404 when the session does not exist.
                    """,
            tags = {"Register Sessions"})
    @GetMapping("/{sessionId}/cash-movements")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_VIEW + "')")
    public ResponseEntity<List<CashMovementResponse>> listCashMovements(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(registerSessionService.listCashMovements(sessionId).stream()
                .map(CashMovementResponse::from)
                .toList());
    }

    @Operation(
            operationId = "beginSessionClose",
            summary = "Begin Closing a Register Session",
            description = """
                    Records the physically counted drawer cash and moves the register session to CLOSING, freezing \
                    the terminal against new orders.
                    Use this tool to start the drawer count at end of shift; do not use confirmSessionClose, which \
                    finalizes a session already in CLOSING.
                    Preconditions: the session must exist, must not already be CLOSED, and none of its orders may \
                    be in PENDING_PAYMENT.
                    Required inputs: countedCash (zero or greater) in the body and sessionId (UUID) as a path \
                    parameter.
                    Emits an ORDER_SESSION_BEGIN_CLOSE event.
                    Returns 200 with the CLOSING session, 404 when the session does not exist, and 409 when the \
                    session is already closed or an order on the session is still awaiting payment.
                    """,
            tags = {"Register Sessions"})
    @PostMapping("/{sessionId}/begin-close")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_CLOSE + "')")
    @EmitEvent(id = "ORDER_SESSION_BEGIN_CLOSE", apiVersion = "1")
    public ResponseEntity<RegisterSessionResponse> beginClose(
            @PathVariable UUID sessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The physically counted drawer cash at the start of the close.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Counted drawer",
                                                            value = "{\"countedCash\":310.00}")))
                    @Valid
                    @RequestBody
                    BeginCloseRequest request) {
        return ResponseEntity.ok(
                RegisterSessionResponse.from(registerSessionService.beginClose(sessionId, request.getCountedCash())));
    }

    @Operation(
            operationId = "confirmSessionClose",
            summary = "Confirm a Register Session Close",
            description = """
                    Finalizes a CLOSING register session: snapshots theoretical cash (opening float plus net CASH \
                    settlements plus signed cash movements), computes the over/short against the counted drawer, \
                    and moves the session to CLOSED.
                    Use this tool to finish the close after the count; do not use beginSessionClose, which records \
                    the count and must run first.
                    Preconditions: the session must be in CLOSING, and no order on the session may have re-entered \
                    PENDING_PAYMENT since the count began.
                    Required inputs: sessionId (UUID) as a path parameter; there is no request body — an \
                    over/short beyond the authorized difference limit (default 5.00, configurable via \
                    pos.order.session.authorized-difference-limit) additionally requires the \
                    order:session:approve_variance permission.
                    Emits an ORDER_SESSION_CONFIRM_CLOSE event and publishes a register-session-closed fact \
                    carrying per-tender totals and the reconciliation figures.
                    Returns 200 with the CLOSED session, 403 when the variance exceeds the limit without the \
                    approval permission, 404 when the session does not exist, and 409 when the session is not in \
                    CLOSING or an order is still awaiting payment.
                    """,
            tags = {"Register Sessions"})
    @PostMapping("/{sessionId}/confirm-close")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_CLOSE + "')")
    @EmitEvent(id = "ORDER_SESSION_CONFIRM_CLOSE", apiVersion = "1")
    public ResponseEntity<RegisterSessionResponse> confirmClose(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(RegisterSessionResponse.from(registerSessionService.confirmClose(sessionId)));
    }

    @Operation(
            operationId = "getSessionXReport",
            summary = "X-Report for a Register Session",
            description = """
                    Returns an interim X-report for a register session: opening float, per-tender totals, cash \
                    settlements, cash movements, theoretical cash, and over/short when a count has been recorded.
                    Use this tool for mid-shift figures while the session is open; use getSessionZReport instead \
                    for the end-of-session close summary.
                    Preconditions: the session must exist; figures are computed live from the session's current \
                    ledger.
                    Required inputs: sessionId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only report projection.
                    Returns 404 when no register session exists for the supplied id.
                    """,
            tags = {"Register Sessions"})
    @GetMapping("/{sessionId}/x-report")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_VIEW + "')")
    public ResponseEntity<SessionReportResponse> xReport(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(SessionReportResponse.from(registerSessionService.xReport(sessionId)));
    }

    @Operation(
            operationId = "getSessionZReport",
            summary = "Z-Report for a Register Session",
            description = """
                    Returns the Z-report close summary for a register session, including per-tender totals, cash \
                    movements, theoretical cash, counted cash, and the over/short variance.
                    Use this tool for the end-of-session summary after close; use getSessionXReport instead for \
                    interim mid-shift figures.
                    Preconditions: the session must exist; the report reflects the session's current ledger, so it \
                    is authoritative once the session is CLOSED.
                    Required inputs: sessionId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only report projection.
                    Returns 404 when no register session exists for the supplied id.
                    """,
            tags = {"Register Sessions"})
    @GetMapping("/{sessionId}/z-report")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_VIEW + "')")
    public ResponseEntity<SessionReportResponse> zReport(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(SessionReportResponse.from(registerSessionService.zReport(sessionId)));
    }
}
