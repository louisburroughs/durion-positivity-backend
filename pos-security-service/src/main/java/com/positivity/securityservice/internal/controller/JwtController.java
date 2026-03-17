package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.securityservice.internal.dto.InternalTokenRequest;
import com.positivity.securityservice.internal.dto.RefreshTokenRequest;
import com.positivity.securityservice.internal.dto.TokenPairRequest;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import com.positivity.securityservice.internal.dto.TokenResponse;
import com.positivity.securityservice.service.JwtService;
import com.positivity.securityservice.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * REST controller for JWT authentication and token management.
 * 
 * **Security Model (ADR-0011):**
 * - API Gateway enforces authentication and injects user context
 * - This service issues and validates JWT tokens
 * - Gateway performs role-to-authority mapping (not this service)
 * 
 * **Endpoints:**
 * - POST /v1/auth/internal/token - Issue single access token (internal-only)
 * - POST /v1/auth/token-pair - Issue access + refresh tokens
 * - POST /v1/auth/refresh - Refresh access token
 * - GET /v1/auth/validate - Validate token
 * - DELETE /v1/auth/revoke - Revoke token
 * - GET /v1/auth/roles - Extract roles from token
 * - GET /v1/auth/authorities - Extract authorities from token
 * - GET /v1/auth/subject - Extract subject (username) from token
 * 
 * **Error Handling:**
 * All exceptions handled by GlobalExceptionHandler with correlation IDs
 * 
 * @since 1.0
 */
@Slf4j
@Tag(name = "JWT API", description = "Endpoints for JWT authentication and token management per ADR-0011")
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class JwtController {
    private final JwtService jwtService;
    private final UserService userService;

    /**
     * Issues a single JWT access token for internal trusted callers.
     * 
     * **Contract:**
     * - Request: POST /v1/auth/internal/token with InternalTokenRequest body
     * - Response: TokenResponse (single accessToken field)
     * - Success: 200 OK
     * - Errors: 400 (invalid request), 403 (forbidden), 500 (server error)
     * 
     * **See BACKEND_CONTRACT_GUIDE.md: Login Endpoint (page 3)**
     * 
     * @param request token issuance request with subject and optional roles
     * @return token response containing access token
     * 
     * @throws IllegalArgumentException if username is blank or roles are empty
     */
    @Operation(summary = "Issue internal JWT access token", description = "Internal endpoint to issue a JWT access token (1-hour expiration). "
            +
            "See BACKEND_CONTRACT_GUIDE.md §Login Endpoint for full specification.")
    @ApiResponse(responseCode = "200", description = "JWT token issued successfully", content = @Content(schema = @Schema(implementation = TokenResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (blank username or empty roles)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden: internal admin context required", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SECURITY_AUTH_INTERNAL_TOKEN_ISSUE", apiVersion = "1")
    @PreAuthorize("hasAuthority('security:token:issue_internal')")
    @PostMapping("/internal/token")
    public ResponseEntity<TokenResponse> issueInternalToken(@Valid @RequestBody InternalTokenRequest request) {
        log.info("Internal token issuance request received: subject={}, rolesCount={}",
                request.subject(), request.roles() != null ? request.roles().size() : 0);

        var user = userService.getUserByUsername(request.subject())
                .orElseThrow(() -> new IllegalArgumentException("User not found for subject: " + request.subject()));
        if (user.getId() == null) {
            throw new IllegalStateException("User exists but id is missing for subject: " + request.subject());
        }

        String token = jwtService.generateToken(
                request.subject(),
                user.getId(),
                request.roles() != null ? request.roles() : Set.of());

        return ResponseEntity.ok(TokenResponse.of(token));
    }

    /**
     * Issues both access and refresh tokens.
     * 
     * **Contract:**
     * - Request: POST /v1/auth/token-pair with TokenPairRequest body
     * - Response: TokenPairResponse (accessToken + refreshToken)
     * - Success: 200 OK
     * - Errors: 400 (invalid request), 409 (concurrency), 500 (server error)
     * 
     * **Token Lifetimes:**
     * - accessToken: 1 hour (3600 seconds)
     * - refreshToken: 7 days (604800 seconds)
     * 
     * **See BACKEND_CONTRACT_GUIDE.md: Token Pair Endpoint (page 4)**
     * 
     * @param request token pair request with username and optional roles
     * @return token pair response containing both tokens
     * 
     * @throws IllegalArgumentException if username is blank or roles are empty
     */
    @Operation(summary = "Issue JWT token pair (access + refresh)", description = "Authenticate and receive both access token (1-hour) and refresh token (7-day). "
            +
            "See BACKEND_CONTRACT_GUIDE.md §Token Pair Endpoint for full specification.")
    @ApiResponse(responseCode = "200", description = "Token pair issued successfully", content = @Content(schema = @Schema(implementation = TokenPairResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (blank username or empty roles)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SECURITY_AUTH_TOKEN_PAIR", apiVersion = "1")
    @PreAuthorize("permitAll()")
    @PostMapping("/token-pair")
    public ResponseEntity<TokenPairResponse> generateTokenPair(
            @Valid @RequestBody TokenPairRequest request) {

        log.info("Token pair request received: subject={}, rolesCount={}",
                request.subject(), request.roles() != null ? request.roles().size() : 0);

        var user = userService.getUserByUsername(request.subject())
                .orElseThrow(() -> new IllegalArgumentException("User not found for subject: " + request.subject()));
        if (user.getId() == null) {
            throw new IllegalStateException("User exists but id is missing for subject: " + request.subject());
        }

        JwtService.TokenPair tokenPair = jwtService.generateTokenPair(
                request.subject(),
                user.getId(),
                null,
                request.roles() != null ? request.roles() : Set.of());

        return ResponseEntity.ok(TokenPairResponse.of(tokenPair.accessToken(), tokenPair.refreshToken()));
    }

    /**
     * Refreshes the access token using a refresh token.
     * 
     * **Contract:**
     * - Request: POST /v1/auth/refresh with RefreshTokenRequest body
     * - Response: TokenPairResponse (new accessToken + refreshToken)
     * - Success: 200 OK
     * - Errors: 400 (invalid token), 409 (concurrency), 500 (server error)
     * 
     * **Process:**
     * 1. Validate refresh token (signature, expiration, revocation, DB presence)
     * 2. Revoke old tokens (Redis + database)
     * 3. Issue new token pair
     * 
     * **See BACKEND_CONTRACT_GUIDE.md: Refresh Endpoint (page 4)**
     * 
     * @param request refresh token request
     * @return token pair response with new tokens
     * 
     * @throws IllegalArgumentException if refresh token is invalid
     */
    @Operation(summary = "Refresh access token using refresh token", description = "Exchange a valid refresh token for a new access token and refresh token. "
            +
            "Old tokens are immediately revoked. " +
            "See BACKEND_CONTRACT_GUIDE.md §Refresh Endpoint for full specification.")
    @ApiResponse(responseCode = "200", description = "New token pair issued successfully", content = @Content(schema = @Schema(implementation = TokenPairResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid refresh token", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Concurrency conflict during token revocation", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SECURITY_AUTH_REFRESH", apiVersion = "1")
    @PreAuthorize("permitAll()")
    @PostMapping("/refresh")
    public ResponseEntity<TokenPairResponse> refreshAccessToken(
            @Valid @RequestBody RefreshTokenRequest request) {

        log.debug("Refresh token request received");

        JwtService.TokenPair tokenPair = jwtService.refreshAccessToken(request.refreshToken());

        return ResponseEntity.ok(TokenPairResponse.of(tokenPair.accessToken(), tokenPair.refreshToken()));
    }

    /**
     * Validates a JWT token.
     * 
     * **Validation Checks:**
     * 1. JWT signature (HMAC-SHA256)
     * 2. Token expiration
     * 3. Revocation status in Redis
     * 4. Database presence
     * 
     * **Contract:**
     * - Request: GET /v1/auth/validate?token=...
     * - Response: { valid: true/false }
     * - Success: 200 OK
     * 
     * @param token JWT token to validate
     * @return validation result
     */
    @Operation(summary = "Validate JWT token", description = "Check if a JWT token is valid (signature, expiration, revocation)")
    @ApiResponse(responseCode = "200", description = "Validation result returned", content = @Content(schema = @Schema(implementation = ValidateResponse.class)))
    @PreAuthorize("permitAll()")
    @GetMapping("/validate")
    public ResponseEntity<ValidateResponse> validateToken(@RequestParam String token) {
        boolean valid = jwtService.validateToken(token);
        return ResponseEntity.ok(new ValidateResponse(valid));
    }

    /**
     * Revokes a JWT token.
     * 
     * **Process:**
     * 1. Extract JTI from token
     * 2. Add JTI to Redis revocation cache with TTL
     * 3. Delete token from database
     * 
     * **Contract:**
     * - Request: DELETE /v1/auth/revoke?token=...
     * - Response: 204 No Content
     * - Success: 204 No Content
     * 
     * @param token JWT token to revoke
     * @return 204 No Content
     */
    @Operation(summary = "Revoke JWT token", description = "Revoke a JWT token immediately (Redis cache + database)")
    @ApiResponse(responseCode = "204", description = "Token revoked successfully")
    @EmitEvent(id = "SECURITY_AUTH_REVOKE", apiVersion = "1")
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/revoke")
    public ResponseEntity<Void> revokeToken(@RequestParam String token) {
        log.debug("Token revocation request received");
        jwtService.deleteToken(token);
        return ResponseEntity.noContent().build();
    }

    /**
     * Extracts and returns roles from a valid JWT token.
     * 
     * @param token JWT token
     * @return set of roles or 401 if token invalid
     */
    @Operation(summary = "Extract roles from JWT token", description = "Get the roles claim from a JWT token")
    @ApiResponse(responseCode = "200", description = "Roles extracted successfully")
    @ApiResponse(responseCode = "401", description = "Token invalid or expired")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/roles")
    public ResponseEntity<Set<String>> getRoles(@RequestParam String token) {
        if (!jwtService.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Set<String> roles = jwtService.getRolesFromToken(token);
        return ResponseEntity.ok(roles);
    }

    /**
     * Extracts and returns authorities from a valid JWT token.
     * 
     * @param token JWT token
     * @return set of authorities or 401 if token invalid
     */
    @Operation(summary = "Extract authorities from JWT token", description = "Get the authorities claim from a JWT token (expanded by gateway)")
    @ApiResponse(responseCode = "200", description = "Authorities extracted successfully")
    @ApiResponse(responseCode = "401", description = "Token invalid or expired")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/authorities")
    public ResponseEntity<Set<String>> getAuthorities(@RequestParam String token) {
        if (!jwtService.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Set<String> authorities = jwtService.getAuthoritiesFromToken(token);
        return ResponseEntity.ok(authorities);
    }

    /**
     * Extracts and returns the subject (username) from a valid JWT token.
     * 
     * @param token JWT token
     * @return subject (username) or 401 if token invalid
     */
    @Operation(summary = "Extract subject from JWT token", description = "Get the subject (username) from a JWT token")
    @ApiResponse(responseCode = "200", description = "Subject extracted successfully")
    @ApiResponse(responseCode = "401", description = "Token invalid or expired")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/subject")
    public ResponseEntity<String> getSubject(@RequestParam String token) {
        if (!jwtService.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String subject = jwtService.getUsernameFromToken(token);
        return ResponseEntity.ok(subject);
    }

    /**
     * Extracts and returns the stable user identifier from a valid JWT token.
     *
     * @param token JWT token
     * @return userId or 401 if token invalid
     */
    @Operation(summary = "Extract userId from JWT token", description = "Get the stable user identifier from a JWT token")
    @ApiResponse(responseCode = "200", description = "userId extracted successfully")
    @ApiResponse(responseCode = "401", description = "Token invalid or expired")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/user-id")
    public ResponseEntity<String> getUserId(@RequestParam String token) {
        if (!jwtService.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(jwtService.getUserIdFromToken(token).toString());
    }

    /**
     * Validation result record.
     */
    @Schema(description = "Token validation result")
    public record ValidateResponse(
            @Schema(description = "Whether the token is valid") Boolean valid) {
    }
}
