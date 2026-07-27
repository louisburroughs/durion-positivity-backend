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

        @Operation(summary = "Open a register session", description = "Opens a new register session for the provided terminal, location, and clerk context.", tags = {
                        "Register Sessions" })
        @PostMapping
        @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_OPEN + "')")
        @EmitEvent(id = "ORDER_SESSION_OPEN", apiVersion = "1")
        public ResponseEntity<RegisterSessionResponse> openSession(@Valid @RequestBody OpenSessionRequest request) {
                RegisterSessionSummary summary = registerSessionService.openSession(new OpenSessionCommand(
                                request.getTerminalId(),
                                request.getLocationId(),
                                request.getOpeningFloat(),
                                request.getOpenedByClerkId()));
                return ResponseEntity.status(HttpStatus.CREATED).body(RegisterSessionResponse.from(summary));
        }

        @Operation(summary = "Get a register session by id", description = "Retrieves register session details and balances for the given session id.", tags = {
                        "Register Sessions" })
        @GetMapping("/{sessionId}")
        @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_VIEW + "')")
        public ResponseEntity<RegisterSessionResponse> getSession(@PathVariable UUID sessionId) {
                return ResponseEntity.ok(RegisterSessionResponse.from(registerSessionService.getSession(sessionId)));
        }

        @Operation(summary = "Get the current open session for a terminal", description = "Returns the OPEN (or CLOSING) session on the terminal, or 204 if none is open.", tags = {
                        "Register Sessions" })
        @GetMapping("/current")
        @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_VIEW + "')")
        public ResponseEntity<RegisterSessionResponse> currentSession(@RequestParam String terminalId) {
                return registerSessionService
                                .currentSessionForTerminal(terminalId)
                                .map(RegisterSessionResponse::from)
                                .map(ResponseEntity::ok)
                                .orElseGet(() -> ResponseEntity.noContent().build());
        }

        @Operation(summary = "Record a drawer cash movement", description = "Records a cash in/out drawer movement against the specified register session.", tags = {
                        "Register Sessions" })
        @PostMapping("/{sessionId}/cash-movements")
        @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_CASH_MOVEMENT + "')")
        @EmitEvent(id = "ORDER_SESSION_CASH_MOVEMENT", apiVersion = "1")
        public ResponseEntity<CashMovementResponse> recordCashMovement(
                        @PathVariable UUID sessionId, @Valid @RequestBody CashMovementRequest request) {
                CashMovementResponse response = CashMovementResponse
                                .from(registerSessionService.recordCashMovement(new CashMovementCommand(
                                                sessionId,
                                                request.getMovementType(),
                                                request.getAmount(),
                                                request.getReason(),
                                                request.getClerkId())));
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        @Operation(summary = "List a session's cash movements", description = "Lists all recorded cash movements for the specified register session.", tags = {
                        "Register Sessions" })
        @GetMapping("/{sessionId}/cash-movements")
        @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_VIEW + "')")
        public ResponseEntity<List<CashMovementResponse>> listCashMovements(@PathVariable UUID sessionId) {
                return ResponseEntity.ok(registerSessionService.listCashMovements(sessionId).stream()
                                .map(CashMovementResponse::from)
                                .toList());
        }

        @Operation(summary = "Begin closing a register session", description = "Records the counted cash and moves the session to CLOSING. Blocked while any of "
                        + "its orders are in PENDING_PAYMENT.", tags = { "Register Sessions" })
        @PostMapping("/{sessionId}/begin-close")
        @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_CLOSE + "')")
        @EmitEvent(id = "ORDER_SESSION_BEGIN_CLOSE", apiVersion = "1")
        public ResponseEntity<RegisterSessionResponse> beginClose(
                        @PathVariable UUID sessionId, @Valid @RequestBody BeginCloseRequest request) {
                return ResponseEntity.ok(
                                RegisterSessionResponse.from(registerSessionService.beginClose(sessionId,
                                                request.getCountedCash())));
        }

        @Operation(summary = "Confirm a register session close", description = "Snapshots theoretical cash, computes over/short, and moves the session to CLOSED. "
                        + "An over/short beyond the authorized difference limit requires "
                        + "order:session:approve_variance.", tags = { "Register Sessions" })
        @PostMapping("/{sessionId}/confirm-close")
        @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_CLOSE + "')")
        @EmitEvent(id = "ORDER_SESSION_CONFIRM_CLOSE", apiVersion = "1")
        public ResponseEntity<RegisterSessionResponse> confirmClose(@PathVariable UUID sessionId) {
                return ResponseEntity.ok(RegisterSessionResponse.from(registerSessionService.confirmClose(sessionId)));
        }

        @Operation(summary = "X-report (mid-day figures for an open session)", description = "Returns an interim session report for an open or in-progress register session.", tags = {
                        "Register Sessions" })
        @GetMapping("/{sessionId}/x-report")
        @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_VIEW + "')")
        public ResponseEntity<SessionReportResponse> xReport(@PathVariable UUID sessionId) {
                return ResponseEntity.ok(SessionReportResponse.from(registerSessionService.xReport(sessionId)));
        }

        @Operation(summary = "Z-report (close summary)", description = "Returns the end-of-session close summary, including totals and variance metrics.", tags = {
                        "Register Sessions" })
        @GetMapping("/{sessionId}/z-report")
        @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_VIEW + "')")
        public ResponseEntity<SessionReportResponse> zReport(@PathVariable UUID sessionId) {
                return ResponseEntity.ok(SessionReportResponse.from(registerSessionService.zReport(sessionId)));
        }
}
