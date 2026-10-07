package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.SessionPolicyResponse;
import com.positivity.order.internal.dto.UpdateSessionPolicyRequest;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.SessionPolicyService;
import com.positivity.order.internal.service.model.SessionPolicyChangeView;
import com.positivity.order.internal.service.model.SessionPolicyView;
import com.positivity.order.internal.service.model.UpdateSessionPolicyCommand;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's drawer policy (CAP:550 S16, #2512; SPEC-accounting-workspace §4.6 "Drawer limits",
 * §7.2, AW19): allowed and cashier limit per movement type and the over/short tolerance. The accounting
 * workspace's Approval limits page reads and writes it through the pos-order SDK (ADR-0041). One policy
 * per tenant, so no location is involved.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/orders/session-policy")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Register Sessions", description = "POS register session and cash management")
public class SessionPolicyController {

    private final SessionPolicyService sessionPolicyService;

    @Operation(
            operationId = "getSessionPolicy",
            summary = "Get the Drawer Policy",
            description = """
                    Returns the tenant's drawer policy and its change history: per movement type (petty expenses, \
                    vendor cash on delivery, bank drop, float change) whether cashiers may record it, the cashier \
                    limit on a session's running total and whether a manager is always needed; the over/short \
                    tolerance above which a close needs order:session:approve_variance; and every change, newest \
                    first. Bank drop and float change are read-only rows.
                    Use this tool to read the policy before changing it; use getCashMovementOptions instead for \
                    what one register session may record now.
                    Preconditions: none — a tenant that never changed the policy gets the defaults (petty \
                    expenses on at 50.00, vendor cash on delivery off, tolerance 5.00).
                    Required inputs: none; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the policy.
                    """,
            tags = {"Register Sessions"})
    @ApiResponse(responseCode = "200", description = "The drawer policy and its history.")
    @GetMapping
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_POLICY_MANAGE + "')")
    public ResponseEntity<SessionPolicyResponse> getSessionPolicy() {
        return ResponseEntity.ok(toResponse(sessionPolicyService.current(), sessionPolicyService.history()));
    }

    @Operation(
            operationId = "updateSessionPolicy",
            summary = "Replace the Drawer Policy",
            description = """
                    Replaces the two configurable movement types (petty expenses and vendor cash on delivery: \
                    allowed and cashier limit) and the over/short tolerance, with a justification. Each changed \
                    setting writes one history row (old and new value, actor, justification); a request that \
                    changes nothing writes nothing. Switching a type off is never retroactive: recorded movements \
                    stand and are carried on the close fact.
                    Use this tool to change the drawer limits after reading them with getSessionPolicy; do not use \
                    it to see what one register session may record now — use getCashMovementOptions instead.
                    Preconditions: an allowed type needs a cashier limit; vendor cash on delivery stays off until \
                    pos-order holds the vendor list.
                    Required inputs: pettyExpense and vendorCod (allowed, cashierLimit), overShortTolerance and a \
                    justification of at least 10 characters; limits and the tolerance must not be negative.
                    Emits an ORDER_SESSION_POLICY_UPDATE event.
                    Returns 200 with the policy and its history, 400 VALIDATION_ERROR for a field rule, and 409 \
                    SESSION_POLICY_CONFLICT when another change won a race (read again and retry).
                    """,
            tags = {"Register Sessions"})
    @ApiResponse(responseCode = "200", description = "The policy after the change.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: justification under 10 characters, a negative limit or tolerance, an"
                    + " allowed type without a limit, or vendor cash on delivery switched on.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "SESSION_POLICY_CONFLICT: another change won a race; read the policy again and retry.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_SESSION_POLICY_MANAGE + "')")
    @EmitEvent(id = "ORDER_SESSION_POLICY_UPDATE", apiVersion = "1")
    public ResponseEntity<SessionPolicyResponse> updateSessionPolicy(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The configurable types, the tolerance and why.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Lower the tolerance", value = """
                                                                    {"pettyExpense":{"allowed":true,"cashierLimit":50.00},
                                                                     "vendorCod":{"allowed":false,"cashierLimit":null},
                                                                     "overShortTolerance":3.00,
                                                                     "justification":"Tighter count after the audit"}
                                                                    """)))
                    @RequestBody
                    UpdateSessionPolicyRequest request) {
        UpdateSessionPolicyRequest.TypeSetting petty = request.getPettyExpense();
        UpdateSessionPolicyRequest.TypeSetting cod = request.getVendorCod();
        SessionPolicyView updated = sessionPolicyService.update(new UpdateSessionPolicyCommand(
                petty == null ? null : petty.getAllowed(),
                petty == null ? null : petty.getCashierLimit(),
                cod == null ? null : cod.getAllowed(),
                cod == null ? null : cod.getCashierLimit(),
                request.getOverShortTolerance(),
                request.getJustification()));
        return ResponseEntity.ok(toResponse(updated, sessionPolicyService.history()));
    }

    private static SessionPolicyResponse toResponse(SessionPolicyView policy, List<SessionPolicyChangeView> history) {
        return new SessionPolicyResponse(
                policy.version(),
                policy.rows().stream()
                        .map(row -> new SessionPolicyResponse.TypePolicy(
                                row.type(),
                                row.allowed(),
                                row.cashierLimit(),
                                row.alwaysNeedsManager(),
                                row.editable()))
                        .toList(),
                policy.overShortTolerance(),
                history.stream()
                        .map(c -> new SessionPolicyResponse.Change(
                                c.setting(), c.oldValue(), c.newValue(), c.actor(), c.justification(), c.changedAt()))
                        .toList());
    }
}
