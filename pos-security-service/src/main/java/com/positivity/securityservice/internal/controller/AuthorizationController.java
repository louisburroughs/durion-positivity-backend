package com.positivity.securityservice.internal.controller;

import com.positivity.securityservice.internal.dto.AuthorizationDecisionResponse;
import com.positivity.securityservice.service.AuthorizationService;
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
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class AuthorizationController {

    private final AuthorizationService authorizationService;

    @GetMapping("/authorization/decision")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuthorizationDecisionResponse> getDecision(
            @RequestParam String principalId,
            @RequestParam(name = "permission") String permission) {
        var decision = authorizationService.authorize(principalId, permission);
        return ResponseEntity.ok(new AuthorizationDecisionResponse(decision.name().toLowerCase()));
    }

    /** this is redundant */
    @GetMapping("/authorize")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuthorizationDecisionResponse> authorize(
            @RequestParam String principalId,
            @RequestParam String permissionKey) {
        var decision = authorizationService.authorize(principalId, permissionKey);
        return ResponseEntity.ok(new AuthorizationDecisionResponse(decision.name().toLowerCase()));
    }
}
