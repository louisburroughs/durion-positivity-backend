package com.positivity.securityservice.internal.controller;

import com.positivity.securityservice.internal.dto.AuthorizationDecisionResponse;
import com.positivity.securityservice.internal.observability.BusinessSpanSupport;
import com.positivity.securityservice.service.AuthorizationService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorization decision endpoints.
 *
 * Issue: #42
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"security:authorization:decide"})
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Tag(name = "Authorization", description = "Authorization decision endpoints for principal permissions")
public class AuthorizationController {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-security-service");
    private static final String DOMAIN = "security-identity";
    private static final String TEAM = "security-eng";

    private final AuthorizationService authorizationService;

    @GetMapping("/authorization/decision")
    @PreAuthorize("hasAuthority('security:authorization:decide')")
    @Operation(
            summary = "Get authorization decision",
            description = "Returns allow or deny for a principal and permission key")
    @ApiResponse(responseCode = "200", description = "Authorization decision returned")
    @ApiResponse(responseCode = "403", description = "Forbidden: authorization decision permission required")
    public ResponseEntity<AuthorizationDecisionResponse> getDecision(
            @Parameter(description = "Principal identifier to evaluate", example = "userA") @RequestParam
                    String principalId,
            @Parameter(description = "Permission key to evaluate", example = "pricing:msrp:edit")
                    @RequestParam(name = "permission")
                    String permission) {
        Span span = TRACER.spanBuilder("Check Authorization Decision").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Check Authorization Decision");
        span.setAttribute("app.operation.type", "query");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            var decision = authorizationService.authorize(principalId, permission);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return ResponseEntity.ok(
                    new AuthorizationDecisionResponse(decision.name().toLowerCase()));
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @GetMapping("/authorization/person-decision")
    @PreAuthorize("hasAuthority('security:authorization:decide')")
    @Operation(
            summary = "Get authorization decision for a person",
            description = "Returns allow or deny for the user backing the given personId and a permission key,"
                    + " evaluated against that user's assigned roles. Used to verify an off-session approver"
                    + " (e.g. a manager identified by employee number) holds a required permission.")
    @ApiResponse(responseCode = "200", description = "Authorization decision returned")
    @ApiResponse(responseCode = "403", description = "Forbidden: authorization decision permission required")
    public ResponseEntity<AuthorizationDecisionResponse> getPersonDecision(
            @Parameter(
                            description = "Person identifier whose backing user is evaluated",
                            example = "123e4567-e89b-12d3-a456-426614174000")
                    @RequestParam
                    UUID personId,
            @Parameter(description = "Permission key to evaluate", example = "invoice:finalize:override")
                    @RequestParam(name = "permission")
                    String permission) {
        var decision = authorizationService.authorizePerson(personId, permission);
        return ResponseEntity.ok(
                new AuthorizationDecisionResponse(decision.name().toLowerCase()));
    }
}
