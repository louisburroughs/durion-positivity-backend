package com.positivity.securityservice.internal.controller;

import com.positivity.securityservice.internal.dto.AuthorizationDecisionResponse;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.service.AuthorizationService;
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

    private final AuthorizationService authorizationService;

    @GetMapping("/authorization/decision")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.AUTHORIZATION_DECIDE + "')")
    @Operation(
            operationId = "getAuthorizationDecision",
            summary = "Get Authorization Decision for a Principal",
            description = """
                    Returns an allow or deny decision for a principal identifier and permission key, evaluated \
                    against the principal-role matrix populated by assignPrincipalRole.
                    Use this tool for matrix-based checks keyed by principal string; use \
                    getPersonAuthorizationDecision instead when the caller has a personId, and checkUserPermission \
                    when it has a user UUID and location.
                    Preconditions: the caller must hold security:authorization:decide; the principal needs no prior \
                    registration.
                    Required inputs: principalId and permission (domain:resource:action) as query parameters.
                    No events are emitted and no state changes; this is a read-only evaluation.
                    Returns 200 with decision allow or deny; an unknown principal or permission yields deny rather \
                    than an error.
                    """)
    @ApiResponse(responseCode = "200", description = "Authorization decision returned")
    @ApiResponse(responseCode = "403", description = "Forbidden: authorization decision permission required")
    public ResponseEntity<AuthorizationDecisionResponse> getDecision(
            @Parameter(description = "Principal identifier to evaluate", example = "userA") @RequestParam
                    String principalId,
            @Parameter(description = "Permission key to evaluate", example = "pricing:msrp:edit")
                    @RequestParam(name = "permission")
                    String permission) {
        var decision = authorizationService.authorize(principalId, permission);
        return ResponseEntity.ok(
                new AuthorizationDecisionResponse(decision.name().toLowerCase()));
    }

    @GetMapping("/authorization/person-decision")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.AUTHORIZATION_DECIDE + "')")
    @Operation(
            operationId = "getPersonAuthorizationDecision",
            summary = "Get Authorization Decision for a Person",
            description = """
                    Returns an allow or deny decision for the user account linked to a personId, evaluated against \
                    that user's directly assigned roles.
                    Use this tool to verify an off-session approver, such as a manager identified by employee \
                    number, holds a required permission; use getAuthorizationDecision instead for matrix principals.
                    Preconditions: the caller must hold security:authorization:decide; a user should be linked to \
                    the person via the user-person link projection.
                    Required inputs: personId (UUID) and permission (domain:resource:action) as query parameters.
                    No events are emitted and no state changes; this is a read-only evaluation.
                    Returns 200 with decision allow or deny; a person with no linked user or without the permission \
                    yields deny rather than an error.
                    """)
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
