package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.dto.ErrorResponse;
import com.positivity.securityservice.internal.dto.SelfRegistrationRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationResponse;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import com.positivity.securityservice.service.AuthenticationService;
import com.positivity.securityservice.service.SelfRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-facing authentication controller.
 *
 * Handles credential-based login and token-refresh requests,
 * delegating to
 * {@link com.positivity.securityservice.service.AuthenticationService}.
 *
 * @since 1.0
 */
@Tag(name = "Auth API", description = "User-facing authentication endpoints")
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationService authenticationService;
    private final SelfRegistrationService selfRegistrationService;

    @Operation(summary = "User login", description = "Authenticates a user with username and password and returns a JWT token pair.")
    @ApiResponse(responseCode = "200", description = "Authentication successful", content = @Content(schema = @Schema(implementation = TokenPairResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing or blank username/password")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    @EmitEvent(id = "SECURITY_AUTH_LOGIN", apiVersion = "1")
    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public ResponseEntity<TokenPairResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authenticationService.login(request));
    }

    @Operation(summary = "Self-register a new user", description = "Creates a low-privilege customer account after resolving or creating a linked person record. Successful registration requires a follow-up login and does not issue tokens immediately. Conflict responses include operator guidance for recovery, linked-account, and CRM identity-review cases.")
    @ApiResponse(responseCode = "201", description = "Self-registration completed", content = @Content(schema = @Schema(implementation = SelfRegistrationResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid registration payload", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Registration blocked because an account or linked person already exists; response includes nextAction and supportAction guidance", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @EmitEvent(id = "SECURITY_AUTH_SELF_REGISTER", apiVersion = "1")
    @PostMapping("/self-register")
    @PreAuthorize("permitAll()")
    public ResponseEntity<SelfRegistrationResponse> selfRegister(@Valid @RequestBody SelfRegistrationRequest request) {
        return ResponseEntity.status(201).body(selfRegistrationService.selfRegister(request));
    }
}
