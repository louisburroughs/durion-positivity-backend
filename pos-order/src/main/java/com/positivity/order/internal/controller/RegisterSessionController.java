package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.BeginCloseRequest;
import com.positivity.order.internal.dto.CashMovementApprovalRequest;
import com.positivity.order.internal.dto.CashMovementApprovalResponse;
import com.positivity.order.internal.dto.CashMovementOptionsResponse;
import com.positivity.order.internal.dto.CashMovementRequest;
import com.positivity.order.internal.dto.CashMovementResponse;
import com.positivity.order.internal.dto.OpenSessionRequest;
import com.positivity.order.internal.dto.RegisterSessionResponse;
import com.positivity.order.internal.dto.RegisterSessionSummary;
import com.positivity.order.internal.dto.SessionReportResponse;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.CashMovementApprovalService;
import com.positivity.order.internal.service.RegisterSessionService;
import com.positivity.order.internal.service.model.CashMovementApprovalCommand;
import com.positivity.order.internal.service.model.CashMovementApprovalResult;
import com.positivity.order.internal.service.model.CashMovementCommand;
import com.positivity.order.internal.service.model.CashMovementOptions;
import com.positivity.order.internal.service.model.CashMovementResult;
import com.positivity.order.internal.service.model.OpenSessionCommand;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
 *
 * <p>Location scope (ADR-0061 §3, #1872): opening a session is gated in
 * {@code RegisterSessionServiceImpl} on the resolved location (the request's {@code locationId},
 * or the terminal's previous session's when omitted) with {@code order:session:open}; reading a
 * session by id is gated here on the stored session's location with {@code order:session:view},
 * after the 404, so the open gate cannot be bypassed by addressing a drawer at another shop.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/orders/sessions")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
@Tag(name = "Register Sessions", description = "POS register session and cash management")
public class RegisterSessionController {

    private static final String OPEN_LOCATION_SCOPE_DENIED_DESCRIPTION =
            "Caller holds order:session:open but its location scope does not cover the session's location"
                    + " (ApiError.code LOCATION_SCOPE_DENIED, see ../durion/docs/architecture/api/ERROR_ENVELOPE.md)";

    private static final String VIEW_LOCATION_SCOPE_DENIED_DESCRIPTION =
            "Caller holds order:session:view but its location scope does not cover the session's location"
                    + " (ApiError.code LOCATION_SCOPE_DENIED, see ../durion/docs/architecture/api/ERROR_ENVELOPE.md)";

    private static final String CASH_MOVEMENT_403_DESCRIPTION = "Refused by the drawer's approval rules:"
            + " CASH_MOVEMENT_APPROVAL_REQUIRED (above the cashier limit on the session's running total, or a float"
            + " change, without an approvalToken), CASH_MOVEMENT_APPROVAL_INVALID (the token is unknown, used, expired"
            + " or issued for another session, reason, amount, category or vendor) or CASH_MOVEMENT_SELF_APPROVAL (the"
            + " token's approver is the caller)";

    private static final String CASH_MOVEMENT_422_DESCRIPTION = "Refused by a drawer rule:"
            + " CASH_MOVEMENT_TYPE_NOT_ALLOWED (the reason's type is switched off in the drawer policy),"
            + " PETTY_EXPENSE_CATEGORY_UNKNOWN (not an ACTIVE petty-expense category) or FLOAT_CHANGE_NOT_RECORDED"
            + " (the amount does not close the gap between the configured float and the drawer's float) or"
            + " CURRENCY_NOT_SUPPORTED (an amount in a currency other than the functional currency)";

    private final RegisterSessionService registerSessionService;
    private final CashMovementApprovalService cashMovementApprovalService;

    @Operation(
            operationId = "openRegisterSession",
            summary = "Open a Register Session",
            description = """
                    Opens an OPEN register (drawer) session on a terminal; sales orders created on the terminal \
                    while it is open bind to it, and it supplies their location by default.
                    Use this tool at the start of a drawer shift; do not use recordCashMovement, which requires a \
                    session that is already open.
                    Preconditions: the terminal must have no session in OPEN or CLOSING — one drawer per terminal. \
                    A caller whose order:session:open grant is location-scoped must have the resolved location \
                    within reach (ADR-0061); a register whose configured float is held at another location than \
                    the resolved one does not open there.
                    Required inputs: terminalId; locationId defaults to the register's float location, else the \
                    terminal's previous session's; the opening float is the configured float (zero when none or \
                    negative) and the opener is the caller, so an openingFloat or openedByClerkId is ignored.
                    Emits an ORDER_SESSION_OPEN event.
                    Returns 201 with the new session, 403 LOCATION_SCOPE_DENIED when the caller's location scope \
                    does not cover the resolved location, 409 when the terminal already has an active session, and \
                    422 REGISTER_FLOAT_LOCATION_MISMATCH when the float is held elsewhere.
                    """,
            tags = {"Register Sessions"})
    @ApiResponse(responseCode = "201", description = "Register session opened.")
    @ApiResponse(
            responseCode = "403",
            description = OPEN_LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The terminal already has an active (OPEN or CLOSING) register session.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description =
                    "REGISTER_FLOAT_LOCATION_MISMATCH: the register's configured float is held at another location"
                            + " than the requested one; fieldErrors name terminalId, requestedLocationId and, when the caller's"
                            + " scope covers it, floatLocationId.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_OPEN + "')")
    @EmitEvent(id = "ORDER_SESSION_OPEN", apiVersion = "1")
    public ResponseEntity<RegisterSessionResponse> openSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The terminal and optional location of the shift.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Morning open", value = """
                                                                    {"terminalId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a90"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    OpenSessionRequest request) {
        RegisterSessionSummary summary = registerSessionService.openSession(
                new OpenSessionCommand(request.getTerminalId(), request.getLocationId()));
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
                    Preconditions: the session must exist. A caller whose order:session:view grant is \
                    location-scoped must have the session's location within reach (ADR-0061).
                    Required inputs: sessionId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no register session exists for the supplied id, and 403 \
                    LOCATION_SCOPE_DENIED when the session exists but its location is outside the caller's scope.
                    """,
            tags = {"Register Sessions"})
    @ApiResponse(responseCode = "200", description = "Register session found.")
    @ApiResponse(
            responseCode = "403",
            description = VIEW_LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No register session exists for the supplied id.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{sessionId}")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_VIEW + "')")
    public ResponseEntity<RegisterSessionResponse> getSession(@PathVariable UUID sessionId) {
        // Existence first (404 from the service), then scope (ADR-0061 §3, #1872): a 403 for an id
        // that does not exist would let a caller probe which session ids are real. This is the
        // resource-addressed sibling of openSession's gate. A session without a location answers
        // "" which a scoped caller cannot cover.
        RegisterSessionSummary summary = registerSessionService.getSession(sessionId);
        UUID sessionLocation = summary.locationId();
        SecurityContextHelper.locationScope()
                .require(
                        OrderPermissions.ORDER_SESSION_VIEW, sessionLocation == null ? "" : sessionLocation.toString());
        return ResponseEntity.ok(RegisterSessionResponse.from(summary));
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
                    Records a drawer cash movement with one of the fixed reasons against an OPEN register session: \
                    PETTY_EXPENSE (out), VENDOR_COD (out), BANK_DROP (out), FLOAT_INCREASE (in) or FLOAT_DECREASE \
                    (out); the direction follows the reason and movements feed the theoretical cash at close.
                    Use this tool for non-sale drawer cash such as a petty expense or the bank drop; use \
                    requestCashMovementApproval first when a manager must approve, and do not use \
                    beginSessionClose, which records the final counted drawer instead.
                    Preconditions: the session must exist and be OPEN; the reason's type must be allowed by the \
                    tenant's drawer policy; a petty expense needs an ACTIVE category; a float movement must match \
                    the difference between the register's configured float and the drawer's float. Above the \
                    cashier limit on the session's running total of the reason, and for every float change, the \
                    request must carry a manager's approvalToken whose approver is not the caller; a caller whose \
                    grant is location-scoped must have the session's location within reach (ADR-0061).
                    Required inputs: requestId (UUIDv7, the idempotency key), reason, a positive amount and its \
                    currencyCode (ISO 4217, the functional currency); \
                    categoryCode, receiptReference and note for PETTY_EXPENSE; vendorId for VENDOR_COD; bagNumber \
                    for BANK_DROP. The cashier is the caller; a clerkId in the body is ignored.
                    Emits an ORDER_SESSION_CASH_MOVEMENT event.
                    Returns 201 with the recorded movement and 200 with the first result when the requestId was \
                    already recorded with the same payload; 400 REGISTER_SESSION_INVALID_ARGUMENT for a missing or \
                    malformed field (VALIDATION_ERROR for a non-ISO currencyCode), 403 for the approval rules or \
                    LOCATION_SCOPE_DENIED, 404 when the session does not exist, 409 \
                    REGISTER_SESSION_CONFLICT when the session is not OPEN or IDEMPOTENCY_CONFLICT when the \
                    requestId was used for another movement, and 422 for a drawer rule or CURRENCY_NOT_SUPPORTED \
                    for a currency other than the functional currency.
                    """,
            tags = {"Register Sessions"})
    @ApiResponse(responseCode = "201", description = "Cash movement recorded.")
    @ApiResponse(responseCode = "200", description = "Replay of an already-recorded requestId: the first result.")
    @ApiResponse(
            responseCode = "400",
            description = "A required field is missing or malformed (REGISTER_SESSION_INVALID_ARGUMENT).",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = CASH_MOVEMENT_403_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No register session exists for the supplied id.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "REGISTER_SESSION_CONFLICT (session not OPEN) or IDEMPOTENCY_CONFLICT (requestId reused or"
                    + " not the first payload).",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = CASH_MOVEMENT_422_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{sessionId}/cash-movements")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_CASH_MOVEMENT + "')")
    @EmitEvent(id = "ORDER_SESSION_CASH_MOVEMENT", apiVersion = "1")
    public ResponseEntity<CashMovementResponse> recordCashMovement(
            @PathVariable UUID sessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The movement: requestId, reason, positive amount, the reason's fields and,"
                                    + " when a manager must approve, the approval token.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = {
                                                @ExampleObject(name = "Petty expense", value = """
                                                                    {"requestId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4ac1",
                                                                     "reason":"PETTY_EXPENSE",
                                                                     "amount":30.00,
                                                                     "currencyCode":"USD",
                                                                     "categoryCode":"SHOP_SUPPLIES",
                                                                     "receiptReference":"R-1001",
                                                                     "note":"Rags and gloves for bay 2"}
                                                                    """),
                                                @ExampleObject(name = "Bank drop", value = """
                                                                    {"requestId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4ac2",
                                                                     "reason":"BANK_DROP",
                                                                     "amount":800.00,
                                                                     "currencyCode":"USD",
                                                                     "bagNumber":"BAG-0042"}
                                                                    """)
                                            }))
                    @Valid
                    @RequestBody
                    CashMovementRequest request) {
        CashMovementResult result = registerSessionService.recordCashMovement(new CashMovementCommand(
                sessionId,
                request.getRequestId(),
                request.getReason(),
                request.getAmount(),
                request.getCurrencyCode(),
                request.getCategoryCode(),
                request.getVendorId(),
                request.getBagNumber(),
                request.getReceiptReference(),
                request.getNote(),
                request.getApprovalToken()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(CashMovementResponse.from(result.movement()));
    }

    @Operation(
            operationId = "requestCashMovementApproval",
            summary = "Approve a Drawer Cash Movement (Manager Step-Up)",
            description = """
                    Verifies a manager's own credentials, entered once at the shared register under the cashier's \
                    sign-in, and returns a single-use approval token for one cash movement. pos-security-service \
                    checks the credentials in the caller's tenant under the sign-in lockout policy; no token is \
                    issued to the manager, no session is opened, and the cashier's session is untouched.
                    Use this tool when recordCashMovement needs a manager (above the cashier limit, or a float \
                    change), then send the token as the movement's approvalToken before it expires; do not use it \
                    to sign the manager in — it issues no sign-in token and opens no session.
                    Preconditions: the session must exist and be OPEN, within the caller's location scope; the \
                    verified person must hold order:session:approve_cash_movement with a location scope that \
                    reaches the session's location, and must not be the caller; after five failed approvals on one \
                    session the step-up refuses without checking.
                    Required inputs: managerUsername, managerPassword, reason, the movement's exact amount and its \
                    currencyCode (the functional currency), plus its categoryCode or vendorId when it has one; the \
                    token is bound to the session, reason, amount, currency and category or vendor, expires after \
                    five minutes and is used once.
                    Emits an ORDER_SESSION_CASH_MOVEMENT_APPROVE event; the password is never stored or logged.
                    Returns 201 with the token and its expiry; 400 for a missing field; 403 \
                    CASH_MOVEMENT_APPROVAL_DENIED for any failed check (wrong or unknown credentials, a locked or \
                    inactive account, or a person without the permission — the same body for every reason, never \
                    401), CASH_MOVEMENT_SELF_APPROVAL for the caller's own credentials, \
                    CASH_MOVEMENT_CALLER_UNIDENTIFIED when the caller's sign-in carries no user id, or \
                    LOCATION_SCOPE_DENIED; 404 when the session does not exist; 409 when it is not OPEN; 422 \
                    CURRENCY_NOT_SUPPORTED for a currency other than the functional currency; 503 when the \
                    credentials could not be checked.
                    """,
            tags = {"Register Sessions"})
    @ApiResponse(responseCode = "201", description = "Approval token issued.")
    @ApiResponse(
            responseCode = "400",
            description = "A required field is missing or malformed (REGISTER_SESSION_INVALID_ARGUMENT).",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "CASH_MOVEMENT_APPROVAL_DENIED (any failed check, one body for every reason),"
                    + " CASH_MOVEMENT_SELF_APPROVAL (the caller's own credentials), CASH_MOVEMENT_CALLER_UNIDENTIFIED"
                    + " (the caller's sign-in has no user id) or LOCATION_SCOPE_DENIED.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No register session exists for the supplied id.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The session is not OPEN (REGISTER_SESSION_CONFLICT).",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "The credentials could not be checked right now (CASH_MOVEMENT_APPROVAL_UNAVAILABLE).",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "CURRENCY_NOT_SUPPORTED: the amount is in a currency other than the functional currency.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{sessionId}/cash-movement-approvals")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_CASH_MOVEMENT + "')")
    @EmitEvent(id = "ORDER_SESSION_CASH_MOVEMENT_APPROVE", apiVersion = "1")
    public ResponseEntity<CashMovementApprovalResponse> requestCashMovementApproval(
            @PathVariable UUID sessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The manager's credentials and the movement the approval is for.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Petty expense over the limit", value = """
                                                                    {"managerUsername":"jane.manager",
                                                                     "managerPassword":"********",
                                                                     "reason":"PETTY_EXPENSE",
                                                                     "amount":25.00,
                                                                     "currencyCode":"USD",
                                                                     "categoryCode":"SHOP_SUPPLIES"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CashMovementApprovalRequest request) {
        CashMovementApprovalResult result = cashMovementApprovalService.approve(new CashMovementApprovalCommand(
                sessionId,
                request.getManagerUsername(),
                request.getManagerPassword(),
                request.getReason(),
                request.getAmount(),
                request.getCurrencyCode(),
                request.getCategoryCode(),
                request.getVendorId()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CashMovementApprovalResponse(
                        result.approvalToken(), result.expiresAt(), result.amount(), result.currencyCode()));
    }

    @Operation(
            operationId = "getCashMovementOptions",
            summary = "Cash Movement Options for a Session",
            description = """
                    Returns what the register may offer the cashier for a session: per fixed reason whether it is \
                    allowed now, its cashier limit, the session's running total, whether a manager is always \
                    needed and the fields it requires; and the ACTIVE petty-expense categories (code, label, \
                    examples).
                    Use this tool to build the drawer cash in/out screen; use getSessionPolicy instead to read or \
                    manage the tenant's policy.
                    Amounts are in the functional currency, stated as currencyCode.
                    Preconditions: the session must exist, within the caller's location scope (ADR-0061).
                    Required inputs: sessionId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no register session exists for the supplied id, and 403 \
                    LOCATION_SCOPE_DENIED when its location is outside the caller's scope.
                    """,
            tags = {"Register Sessions"})
    @ApiResponse(responseCode = "200", description = "Options for the session.")
    @ApiResponse(
            responseCode = "404",
            description = "No register session exists for the supplied id.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "LOCATION_SCOPE_DENIED: the session's location is outside the caller's scope.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{sessionId}/cash-movement-options")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_CASH_MOVEMENT + "')")
    public ResponseEntity<CashMovementOptionsResponse> cashMovementOptions(@PathVariable UUID sessionId) {
        CashMovementOptions options = registerSessionService.cashMovementOptions(sessionId);
        return ResponseEntity.ok(new CashMovementOptionsResponse(
                options.sessionId(),
                options.currencyCode(),
                options.reasons().stream()
                        .map(r -> new CashMovementOptionsResponse.ReasonOption(
                                r.reason(),
                                r.direction(),
                                r.allowedNow(),
                                r.cashierLimit(),
                                r.runningTotal(),
                                r.alwaysNeedsManager(),
                                r.requiredFields()))
                        .toList(),
                options.categories().stream()
                        .map(c -> new CashMovementOptionsResponse.CategoryOption(c.code(), c.label(), c.examples()))
                        .toList()));
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
                    over/short beyond the tenant's over/short tolerance (drawer policy, default 5.00) additionally \
                    requires the order:session:approve_variance permission.
                    Emits an ORDER_SESSION_CONFIRM_CLOSE event and publishes a register-session-closed fact \
                    (schema version 2) carrying per-tender totals, the reconciliation figures and every cash \
                    movement with its reason, amount, details, cashier and approver.
                    Returns 200 with the CLOSED session, 403 when the variance exceeds the tolerance without the \
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
                    settlements, cash movements with their reason and details, theoretical cash, and over/short \
                    when a count has been recorded.
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
