package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.ActivateAccountRequest;
import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationResponse;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import com.positivity.securityservice.internal.service.AdministratorActivationService;
import com.positivity.securityservice.internal.service.AuthenticationService;
import com.positivity.securityservice.internal.service.SelfRegistrationService;
import com.positivity.shared.error.ApiError;
import com.positivity.tenancy.TenantHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-facing authentication controller.
 *
 * Handles credential-based login and token-refresh requests,
 * delegating to
 * {@link com.positivity.securityservice.internal.service.AuthenticationService}.
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
    private final AdministratorActivationService administratorActivationService;

    @Operation(operationId = "loginUser", summary = "Authenticate User and Issue Tokens", description = """
                    Authenticates a user with username and password and returns a JWT access token (1-hour) and \
                    refresh token (7-day) carrying uid, roles, perm_bits, and perm_ver claims.
                    Use this tool when a person signs in with credentials; do not use refreshTokenPair, which \
                    exchanges an existing refresh token, and do not use issueInternalToken, which mints tokens for \
                    trusted internal callers without a password.
                    Preconditions: the user account must exist, be enabled, non-expired, hold unexpired credentials, \
                    and not be inside an active failed-login lockout window.
                    Required inputs: username and password, both non-blank.
                    Emits a SECURITY_AUTH_LOGIN event, resets the failed-attempt counter on success, and persists \
                    the issued token pair for later validation and revocation.
                    Returns 401 with code ACCOUNT_LOCKED while the lockout window is active, INVALID_CREDENTIALS on \
                    a bad password, and ACCOUNT_DISABLED, ACCOUNT_EXPIRED, or CREDENTIALS_EXPIRED for the matching \
                    account states; and 403 with USER_HAS_NO_ROLES when the credentials are valid but the account \
                    currently has no roles assigned.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Authentication successful",
            content = @Content(schema = @Schema(implementation = TokenPairResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing or blank username/password")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    @ApiResponse(
            responseCode = "403",
            description = "USER_HAS_NO_ROLES: the credentials are valid, but the account currently has no roles "
                    + "assigned",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "500",
            description = "Internal server error",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SECURITY_AUTH_LOGIN", apiVersion = "1")
    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public ResponseEntity<TokenPairResponse> login(
            @Parameter(hidden = true) @RequestHeader(value = TenantHeaders.HTTP_TENANT_SLUG, required = false)
                    String tenantSlugHeader,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Credentials of the user signing in.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Credential login", value = """
                                                                    {"username":"jane.doe","password":"Sup3rS3cret!"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    LoginRequest request) {
        return ResponseEntity.ok(authenticationService.login(request, tenantSlugHeader));
    }

    @Operation(operationId = "selfRegisterUser", summary = "Self-Register a New Customer Account", description = """
                    Creates a low-privilege SELF_SERVICE_CUSTOMER account for an anonymous person, resolving or \
                    creating a linked person record before any account row is written.
                    Use this tool when a customer registers themselves; do not use createUser, the operator-facing \
                    endpoint that provisions accounts with arbitrary roles and no identity resolution.
                    Preconditions: no active user may exist for the requested or email-derived username, the \
                    resolved person must not already have an active linked user, and CRM identity signals must not \
                    require manual review.
                    Required inputs: email, password, firstName, and lastName; username is optional and defaults to \
                    the email local part, phone and idpSubject are optional, and idempotencyKey optionally replays a \
                    completed attempt instead of duplicating it.
                    Emits a SECURITY_AUTH_SELF_REGISTER event, creates the user, and queues an asynchronous \
                    user-person link command, so the response reports linkStatus PENDING and issuedTokens false; a \
                    follow-up loginUser call is required to obtain tokens.
                    Returns 409 with code USER_ALREADY_EXISTS, ACCOUNT_RECOVERY_REQUIRED, \
                    PERSON_ALREADY_HAS_ACTIVE_USER, CRM_PERSON_CONFLICT, or IDEMPOTENCY_KEY_REUSED; recovery and \
                    identity conflicts also open a review case and return its id as referenceId with nextAction and \
                    supportAction guidance.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Self-registration completed",
            content = @Content(schema = @Schema(implementation = SelfRegistrationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid registration payload",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "Registration blocked because an account or linked person already exists; response includes nextAction and supportAction guidance",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SECURITY_AUTH_SELF_REGISTER", apiVersion = "1")
    @PostMapping("/self-register")
    @PreAuthorize("permitAll()")
    public ResponseEntity<SelfRegistrationResponse> selfRegister(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Identity and credential details of the person registering themselves.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Customer self-registration", value = """
                                                                    {"email":"jane.smith@example.com",
                                                                     "password":"Sup3rS3cret!",
                                                                     "firstName":"Jane",
                                                                     "lastName":"Smith",
                                                                     "phone":"+15551234567",
                                                                     "idempotencyKey":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    SelfRegistrationRequest request) {
        return ResponseEntity.status(201).body(selfRegistrationService.selfRegister(request));
    }

    @Operation(
            operationId = "activateAccount",
            summary = "Activate an Account with a One-Time Token",
            description = """
                    Exchanges a one-time activation token for the account's first password: sets the password, \
                    clears the credential expiry provisioning left on the account, and marks the token used, all in \
                    one transaction under the token's tenant.
                    Use this tool when a tenant's first administrator has received an activation token from a \
                    platform operator (mintAdministratorActivationToken); do not use loginUser, which cannot \
                    succeed until the account is activated, and do not use updateUser, which needs an \
                    authenticated caller.
                    Preconditions: none on the caller — the endpoint is unauthenticated and binds no tenant; the \
                    token must be unexpired (72 hours from minting) and unused.
                    Required inputs: token and newPassword, both non-blank.
                    Emits a SECURITY_AUTH_ACTIVATE event; no tokens are issued, so a follow-up loginUser call is \
                    required.
                    Returns 204 on success; 400 on a blank field; 401 with ACTIVATION_TOKEN_INVALID when the token \
                    is unknown, expired or already used (one code on purpose, so nothing about the account or the \
                    token's history is revealed).
                    """)
    @ApiResponse(responseCode = "204", description = "Password set; the account can sign in")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or blank token/newPassword",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "ACTIVATION_TOKEN_INVALID: the token is unknown, expired or already used",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SECURITY_AUTH_ACTIVATE", apiVersion = "1")
    @PostMapping("/activate")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> activate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The activation token and the password to set.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Activation", value = """
                                                                    {"token":"Qm9iIGlzIG5vdCBhIHJlYWwgdG9rZW4gYnV0IGxvb2tzIGxpa2Ugb25l",
                                                                     "newPassword":"Sup3rS3cret!"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ActivateAccountRequest request) {
        administratorActivationService.activate(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
