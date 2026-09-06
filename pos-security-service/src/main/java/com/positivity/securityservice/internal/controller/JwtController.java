package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.LogSanitizer;
import com.positivity.securityservice.internal.dto.InternalTokenRequest;
import com.positivity.securityservice.internal.dto.RefreshTokenRequest;
import com.positivity.securityservice.internal.dto.TokenPairRequest;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import com.positivity.securityservice.internal.dto.TokenResponse;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.exception.TokenUserIdMissingException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.securityservice.internal.service.UserService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
 * - GET /v1/auth/authorities - Extract authorities from token (legacy
 * compatibility endpoint; deprecated for new integrations)
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

    /**
     * Client-facing message for a token-issuance request whose subject does not resolve to a
     * user. Deliberately says nothing about the subject or about why it was rejected — see the
     * comment at the throw site (issue #1715).
     */
    private static final String TOKEN_REQUEST_REJECTED = "Token issuance request is invalid";

    /**
     * 400 message for a token-issuance request with no subject. Request shape, so it is decided
     * before the user lookup: a blank subject must not fall through to the 404 that a subject
     * which does not resolve answers (ADR-0017 §2 — 400 is the request-shape answer, 404 the
     * domain-condition answer, and one condition must not answer both).
     */
    private static final String SUBJECT_REQUIRED = "Subject cannot be blank";

    /**
     * Client-facing message for {@link #getUserId} when a valid token carries no user id claim.
     */
    private static final String TOKEN_USER_ID_MISSING = "Token does not carry a uid or userId claim";

    private final JwtService jwtService;
    private final UserService userService;

    /**
     * Issues a single JWT access token for internal trusted callers.
     *
     * @param request token issuance request with subject and optional roles
     * @return token response containing access token
     */
    @Operation(operationId = "issueInternalToken", summary = "Issue Internal JWT Access Token", description = """
                    Issues a single JWT access token (1-hour expiry) for an existing user's username on behalf of a \
                    trusted internal caller, without checking a password.
                    Use this tool for internal service-to-service token minting when only an access token is \
                    needed; do not use issueTokenPair, which also returns a 7-day refresh token, and do not use \
                    loginUser, which authenticates with credentials.
                    Preconditions: the caller must hold security:token:issue_internal, and a user must already \
                    exist for the subject username.
                    Required inputs: subject, an existing username; roles is nominally optional, but an empty \
                    effective role set is rejected, so supply at least one role name.
                    Emits a SECURITY_AUTH_INTERNAL_TOKEN_ISSUE event and persists the issued token so validateToken \
                    and revokeToken recognize it.
                    Returns 400 when the subject is blank or the role set is empty, and 404 with USER_NOT_FOUND when \
                    no user exists for the subject; the 404 body is generic and never names the subject.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "JWT token issued successfully",
            content = @Content(schema = @Schema(implementation = TokenResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request (blank username or empty roles)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden: internal admin context required",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No user exists for the subject (USER_NOT_FOUND); the body never names the subject",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "500",
            description = "Internal server error",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:token:issue_internal"})
    @EmitEvent(id = "SECURITY_AUTH_INTERNAL_TOKEN_ISSUE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.TOKEN_ISSUE_INTERNAL + "')")
    @PostMapping("/internal/token")
    public ResponseEntity<TokenResponse> issueInternalToken(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Subject and role names to embed in the internally issued access token.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Internal token request", value = """
                                                                    {"subject":"svc.reporting","roles":["SHOP_MGR"]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    InternalTokenRequest request) {
        log.info(
                "Internal token issuance request received: subject={}, rolesCount={}",
                LogSanitizer.forLog(request.subject()),
                request.roles() != null ? request.roles().size() : 0);

        if (request.subject() == null || request.subject().isBlank()) {
            throw new SecurityValidationException(SUBJECT_REQUIRED);
        }
        // An unresolvable subject is a domain condition — a referenced resource that does not
        // exist — so it answers 404 USER_NOT_FOUND like every other user reference in this module
        // (ADR-0017 §1; §2 "one condition, one status", encoded on UserNotFoundException rather
        // than here; #1802). The body must still not say *why*: echoing "User not found for
        // subject: X" turned this endpoint into an account-existence oracle for any caller holding
        // security:token:issue_internal (issue #1715). Both refusal paths below therefore answer
        // the identical generic message, and the real reason rides along as the exception's
        // logDetail, which GlobalExceptionHandler logs against the response's correlation id and
        // never puts in the ApiError body (ADR-0056 §1: rejected values are never echoed). The
        // subject is sanitised on the way to the log because it is unvalidated request text and
        // could otherwise forge log lines (CWE-117).
        var user = userService
                .getUserByUsername(request.subject())
                .orElseThrow(() -> UserNotFoundException.withLogDetail(
                        TOKEN_REQUEST_REJECTED,
                        "no user exists for subject: " + LogSanitizer.forLog(request.subject())));
        if (user.getId() == null) {
            // A persisted user row with no primary key is a server-side data defect, so ADR-0017
            // §1 would put it at 500; it answers the same 404 as the branch above because the
            // alternative discloses what that branch exists to hide — a distinct status or code
            // tells the caller the subject resolved. Unreachable by construction (id is the PK of
            // a row just loaded); the defect is diagnosable from the logDetail.
            throw UserNotFoundException.withLogDetail(
                    TOKEN_REQUEST_REJECTED,
                    "user record has no id for subject: " + LogSanitizer.forLog(request.subject()));
        }

        String token = jwtService.generateToken(
                request.subject(), user.getId(), request.roles() != null ? request.roles() : Set.of());

        return ResponseEntity.ok(TokenResponse.of(token));
    }

    /**
     * Issues both access and refresh tokens.
     *
     * @param request token pair request with username and optional roles
     * @return token pair response containing both tokens
     */
    @Operation(operationId = "issueTokenPair", summary = "Issue Privileged JWT Token Pair", description = """
                    Privileged internal endpoint that issues a JWT access token (1-hour) and refresh token (7-day) \
                    for an existing user's username on behalf of a trusted internal caller, embedding uid, roles, \
                    perm_bits, and perm_ver claims.
                    Use this tool when an internal caller needs a refreshable session for an existing user; do not \
                    use issueInternalToken, which returns only a single access token, and do not use loginUser, \
                    which requires the user's password.
                    Preconditions: the caller must hold security:token:issue_internal, and a user must already \
                    exist for the subject username.
                    Required inputs: subject, an existing username, and at least one role name in roles; roles are \
                    expanded to authorities and encoded into the perm_bits bitset claim.
                    Emits a SECURITY_AUTH_TOKEN_PAIR event and persists the pair so validateToken and \
                    refreshTokenPair recognize it.
                    Returns 400 when the subject is blank or the role set is empty, and 404 with USER_NOT_FOUND when \
                    no user exists for the subject; the 404 body is generic and never names the subject.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Token pair issued successfully",
            content = @Content(schema = @Schema(implementation = TokenPairResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request (blank username or empty roles)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden: missing security:token:issue_internal permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No user exists for the subject (USER_NOT_FOUND); the body never names the subject",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "500",
            description = "Internal server error",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:token:issue_internal"})
    @EmitEvent(id = "SECURITY_AUTH_TOKEN_PAIR", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.TOKEN_ISSUE_INTERNAL + "')")
    @PostMapping("/token-pair")
    public ResponseEntity<TokenPairResponse> generateTokenPair(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Subject and role names to embed in the issued access and refresh tokens.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Token pair request", value = """
                                                                    {"subject":"jane.doe","roles":["SHOP_MGR","ACCOUNTING_CLERK"]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    TokenPairRequest request) {

        log.info(
                "Token pair request received: subject={}, rolesCount={}",
                LogSanitizer.forLog(request.subject()),
                request.roles() != null ? request.roles().size() : 0);

        if (request.subject() == null || request.subject().isBlank()) {
            throw new SecurityValidationException(SUBJECT_REQUIRED);
        }
        // An unresolvable subject is a domain condition — a referenced resource that does not
        // exist — so it answers 404 USER_NOT_FOUND like every other user reference in this module
        // (ADR-0017 §1; §2 "one condition, one status", encoded on UserNotFoundException rather
        // than here; #1802). The body must still not say *why*: echoing "User not found for
        // subject: X" turned this endpoint into an account-existence oracle for any caller holding
        // security:token:issue_internal (issue #1715). Both refusal paths below therefore answer
        // the identical generic message, and the real reason rides along as the exception's
        // logDetail, which GlobalExceptionHandler logs against the response's correlation id and
        // never puts in the ApiError body (ADR-0056 §1: rejected values are never echoed). The
        // subject is sanitised on the way to the log because it is unvalidated request text and
        // could otherwise forge log lines (CWE-117).
        var user = userService
                .getUserByUsername(request.subject())
                .orElseThrow(() -> UserNotFoundException.withLogDetail(
                        TOKEN_REQUEST_REJECTED,
                        "no user exists for subject: " + LogSanitizer.forLog(request.subject())));
        if (user.getId() == null) {
            // A persisted user row with no primary key is a server-side data defect, so ADR-0017
            // §1 would put it at 500; it answers the same 404 as the branch above because the
            // alternative discloses what that branch exists to hide — a distinct status or code
            // tells the caller the subject resolved. Unreachable by construction (id is the PK of
            // a row just loaded); the defect is diagnosable from the logDetail.
            throw UserNotFoundException.withLogDetail(
                    TOKEN_REQUEST_REJECTED,
                    "user record has no id for subject: " + LogSanitizer.forLog(request.subject()));
        }

        JwtService.TokenPair tokenPair = jwtService.generateTokenPair(
                request.subject(), user.getId(), null, request.roles() != null ? request.roles() : Set.of());

        return ResponseEntity.ok(TokenPairResponse.of(tokenPair.accessToken(), tokenPair.refreshToken()));
    }

    /**
     * Refreshes the access token using a refresh token.
     *
     * @param request refresh token request
     * @return token pair response with new tokens
     */
    @Operation(operationId = "refreshTokenPair", summary = "Refresh Access Token With Rotation", description = """
                    Exchanges a valid refresh token for a new access and refresh token pair, revoking the old pair \
                    in the same call (token rotation).
                    Use this tool when an access token nears expiry and the client still holds a refresh token; do \
                    not use loginUser, which requires credentials, and do not use validateToken, which only checks \
                    a token without renewing it.
                    Preconditions: the refresh token must be unexpired, unrevoked, present in the token store, and \
                    its user must still exist with at least one role.
                    Required inputs: refreshToken, the exact refresh token string previously issued.
                    Emits a SECURITY_AUTH_REFRESH event, revokes the old access and refresh token JTIs in Redis, \
                    deletes the stored pair, and persists the replacement pair.
                    Returns 400 when the refresh token is invalid, expired, revoked, or unknown; 401 with \
                    INVALID_REFRESH_TOKEN when the referenced user no longer exists; 409 when concurrent refreshes \
                    race on the same token; and 403 with USER_HAS_NO_ROLES when the referenced user currently has \
                    no roles assigned — the token itself is valid, but the account holds no effective \
                    permissions until an administrator assigns a role.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "New token pair issued successfully",
            content = @Content(schema = @Schema(implementation = TokenPairResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid refresh token",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Refresh token is invalid, revoked, or references a user that no longer exists",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Concurrency conflict during token revocation",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "USER_HAS_NO_ROLES: the refresh token is valid, but the referenced user currently has no "
                    + "roles assigned",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "500",
            description = "Internal server error",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SECURITY_AUTH_REFRESH", apiVersion = "1")
    @PreAuthorize("permitAll()")
    @PostMapping("/refresh")
    public ResponseEntity<TokenPairResponse> refreshAccessToken(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The refresh token being exchanged for a new token pair.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Refresh request", value = """
                                                                    {"refreshToken":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJqdGkiOiIwMThmMGExYi0yYzNkLTdlNGYtOGE5Yi0wYzFkMmUzZjRhNWIifQ.sig"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RefreshTokenRequest request) {

        log.debug("Refresh token request received");

        JwtService.TokenPair tokenPair = jwtService.refreshAccessToken(request.refreshToken());

        return ResponseEntity.ok(TokenPairResponse.of(tokenPair.accessToken(), tokenPair.refreshToken()));
    }

    /**
     * Validates a JWT token.
     *
     * @param token JWT token to validate
     * @return validation result
     */
    @Operation(operationId = "validateToken", summary = "Validate a JWT Access Token", description = """
                    Checks whether a JWT access token is currently valid by verifying signature, issuer, audience, \
                    expiry, Redis revocation status, and presence in the token store.
                    Use this tool to test a token without side effects; do not use refreshTokenPair, which rotates \
                    tokens, and do not use revokeToken, which invalidates one.
                    Preconditions: none beyond possessing the token string; the endpoint is public.
                    Required inputs: token as a query parameter.
                    No events are emitted and no state changes; this is a read-only check.
                    Returns 200 in all cases with a valid flag, so callers must read valid=false rather than expect \
                    an error status when the token fails any check.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Validation result returned",
            content = @Content(schema = @Schema(implementation = ValidateResponse.class)))
    @PreAuthorize("permitAll()")
    @GetMapping("/validate")
    public ResponseEntity<ValidateResponse> validateToken(@RequestParam String token) {
        boolean valid = jwtService.validateToken(token);
        return ResponseEntity.ok(new ValidateResponse(valid));
    }

    /**
     * Revokes a JWT token.
     *
     * @param token JWT token to revoke
     * @return 204 No Content
     */
    @Operation(operationId = "revokeToken", summary = "Revoke a JWT Access Token", description = """
                    Revokes a JWT access token immediately by deleting its stored pair and adding its JTI to the \
                    Redis revocation cache until natural expiry.
                    Use this tool to invalidate one compromised or abandoned token; do not use disableUserAccount, \
                    which revokes every token for a user and blocks future sign-in.
                    Preconditions: an authenticated caller; the token should exist in the token store, though an \
                    unknown token is silently ignored.
                    Required inputs: token as a query parameter, the exact access token string to revoke.
                    Emits a SECURITY_AUTH_REVOKE event; revocation takes effect immediately for validateToken and \
                    downstream gateway checks.
                    Returns 204 in all cases, including when the token was not found, so revocation success cannot \
                    be inferred from the status code.
                    """)
    @ApiResponse(responseCode = "204", description = "Token revoked successfully")
    @ApiResponse(
            responseCode = "409",
            description = "Token was modified concurrently; retry with backoff",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
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
    @Operation(operationId = "getTokenRoles", summary = "Extract Roles From JWT Token", description = """
                    Extracts the roles claim from a valid JWT token and returns the normalized role names.
                    Use this tool when a caller holds a token and needs its roles; use getTokenSubject instead for \
                    the username, and decodePermissionBits instead to expand the token's perm_bits claim into \
                    permission codes.
                    Preconditions: the token must pass full validation, including revocation and token-store checks.
                    Required inputs: token as a query parameter.
                    No events are emitted and no state changes; this is a read-only claim extraction.
                    Returns 401 when the token is invalid, expired, revoked, or unknown to the token store.
                    """)
    @ApiResponse(responseCode = "200", description = "Roles extracted successfully")
    @ApiResponse(responseCode = "401", description = "Token invalid or expired")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
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
     * Extracts and returns the subject (username) from a valid JWT token.
     *
     * @param token JWT token
     * @return subject (username) or 401 if token invalid
     */
    @Operation(operationId = "getTokenSubject", summary = "Extract Subject From JWT Token", description = """
                    Extracts the subject claim, the username, from a valid JWT token and returns it as a plain \
                    string body.
                    Use this tool to resolve which user a token belongs to; use getTokenUserId instead for the \
                    stable UUID identifier, and getTokenRoles for the role claim.
                    Preconditions: the token must pass full validation, including revocation and token-store checks.
                    Required inputs: token as a query parameter.
                    No events are emitted and no state changes; this is a read-only claim extraction.
                    Returns 401 when the token is invalid, expired, revoked, or unknown to the token store.
                    """)
    @ApiResponse(responseCode = "200", description = "Subject extracted successfully")
    @ApiResponse(responseCode = "401", description = "Token invalid or expired")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
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
    @Operation(operationId = "getTokenUserId", summary = "Extract User Id From JWT Token", description = """
                    Extracts the stable user identifier from a valid JWT token's uid claim (falling back to the \
                    legacy userId claim) and returns it as a plain string body.
                    Use this tool to resolve the user UUID behind a token; use getTokenSubject instead when the \
                    username is what is needed.
                    Preconditions: the token must pass full validation, including revocation and token-store checks.
                    Required inputs: token as a query parameter.
                    No events are emitted and no state changes; this is a read-only claim extraction.
                    Returns 401 when the token is invalid, expired, revoked, or unknown to the token store, and \
                    422 with TOKEN_USER_ID_MISSING when the token is valid but carries neither a uid nor a legacy \
                    userId claim.
                    """)
    @ApiResponse(responseCode = "200", description = "userId extracted successfully")
    @ApiResponse(responseCode = "401", description = "Token invalid or expired")
    @ApiResponse(
            responseCode = "422",
            description = "Token is valid but carries no uid or userId claim (TOKEN_USER_ID_MISSING)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/user-id")
    public ResponseEntity<String> getUserId(@RequestParam String token) {
        if (!jwtService.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // getUserIdFromToken returns null by design for a token with neither claim (#1803). That
        // is a property of the caller's token, not a server fault, so it is answered deliberately
        // as a 422 instead of letting a NullPointerException reach the generic 500 fallback.
        UUID userId = jwtService.getUserIdFromToken(token);
        if (userId == null) {
            throw new TokenUserIdMissingException(TOKEN_USER_ID_MISSING);
        }
        return ResponseEntity.ok(userId.toString());
    }

    /**
     * Validation result record.
     */
    @Schema(description = "Token validation result")
    public record ValidateResponse(
            @Schema(description = "Whether the token is valid")
            Boolean valid) {}
}
