package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.exception.DuplicateRoleNameException;
import com.positivity.securityservice.internal.exception.InvalidRefreshTokenException;
import com.positivity.securityservice.internal.exception.PermissionNotFoundException;
import com.positivity.securityservice.internal.exception.RoleAssignmentNotFoundException;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.service.AuditEventService;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.WebRequest;

/**
 * Unit tests for GlobalExceptionHandler — covers PERM-004, AUTH-003, and
 * AUTH-004 handlers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlobalExceptionHandlerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String CORRELATION_ID = "test-correlation-id";

    @Mock
    Clock clock;

    @Mock
    ObjectProvider<AuditEventService> auditEventServiceProvider;

    @InjectMocks
    GlobalExceptionHandler sut;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(TEST_CLOCK.instant());
        when(clock.getZone()).thenReturn(TEST_CLOCK.getZone());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private WebRequest requestWithHeader() {
        WebRequest req = mock(WebRequest.class);
        when(req.getHeader("X-Correlation-Id")).thenReturn(CORRELATION_ID);
        return req;
    }

    private WebRequest requestWithoutHeader() {
        WebRequest req = mock(WebRequest.class);
        when(req.getHeader("X-Correlation-Id")).thenReturn(null);
        return req;
    }

    private WebRequest requestWithBlankHeader() {
        WebRequest req = mock(WebRequest.class);
        when(req.getHeader("X-Correlation-Id")).thenReturn("   ");
        return req;
    }

    // ---------------------------------------------------------------
    // Existing PERM-004 tests (preserved)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("handleInvalidRefreshTokenException returns 401 Unauthorized with INVALID_REFRESH_TOKEN code")
    void handleInvalidRefreshTokenException_returns401WithCorrectErrorCode() {
        InvalidRefreshTokenException ex =
                new InvalidRefreshTokenException("Refresh token references a user that no longer exists");
        WebRequest request = mock(WebRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(CORRELATION_ID);

        ResponseEntity<ApiError> response = sut.handleInvalidRefreshTokenException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(response.getBody().message()).isEqualTo("Refresh token references a user that no longer exists");
        assertThat(response.getBody().status()).isEqualTo(401);
        assertThat(response.getBody().correlationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    @DisplayName("handleInvalidRefreshTokenException generates correlation ID when header is absent")
    void handleInvalidRefreshTokenException_generatesCorrelationIdWhenHeaderAbsent() {
        InvalidRefreshTokenException ex = new InvalidRefreshTokenException("user gone");
        WebRequest request = mock(WebRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(null);

        ResponseEntity<ApiError> response = sut.handleInvalidRefreshTokenException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(response.getBody().correlationId()).isNotBlank();
    }

    // ---------------------------------------------------------------
    // AUTH-003: handleLockedException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleLockedException")
    class HandleLockedException {

        @Test
        @DisplayName("returns 401 ACCOUNT_LOCKED with correlation ID from header")
        void returns401WithCorrelationIdFromHeader() {
            LockedException ex = new LockedException("Account locked");

            ResponseEntity<ApiError> response = sut.handleLockedException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("ACCOUNT_LOCKED");
            assertThat(response.getBody().status()).isEqualTo(401);
            assertThat(response.getBody().correlationId()).isEqualTo(CORRELATION_ID);
        }

        @Test
        @DisplayName("generates correlation ID when header is absent")
        void generatesCorrelationIdWhenHeaderAbsent() {
            LockedException ex = new LockedException("Account locked");

            ResponseEntity<ApiError> response = sut.handleLockedException(ex, requestWithoutHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("ACCOUNT_LOCKED");
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("generates correlation ID when header is blank")
        void generatesCorrelationIdWhenHeaderIsBlank() {
            LockedException ex = new LockedException("Account locked");

            ResponseEntity<ApiError> response = sut.handleLockedException(ex, requestWithBlankHeader());

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().correlationId()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotEqualTo("   ");
        }
    }

    // ---------------------------------------------------------------
    // handleBadCredentialsException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleBadCredentialsException")
    class HandleBadCredentialsException {

        @Test
        @DisplayName("returns 401 INVALID_CREDENTIALS")
        void returns401InvalidCredentials() {
            BadCredentialsException ex = new BadCredentialsException("bad password");

            ResponseEntity<ApiError> response = sut.handleBadCredentialsException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(response.getBody().correlationId()).isEqualTo(CORRELATION_ID);
        }
    }

    // ---------------------------------------------------------------
    // handleRoleNotFoundException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleRoleNotFoundException")
    class HandleRoleNotFoundException {

        @Test
        @DisplayName("returns 404 ROLE_NOT_FOUND")
        void returns404RoleNotFound() {
            RoleNotFoundException ex = new RoleNotFoundException("Role not found: ADMIN");

            ResponseEntity<ApiError> response = sut.handleRoleNotFoundException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("ROLE_NOT_FOUND");
            assertThat(response.getBody().message()).isEqualTo("Role not found: ADMIN");
            assertThat(response.getBody().correlationId()).isEqualTo(CORRELATION_ID);
        }
    }

    // ---------------------------------------------------------------
    // handleUserNotFoundException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleUserNotFoundException")
    class HandleUserNotFoundException {

        @Test
        @DisplayName("returns 404 USER_NOT_FOUND")
        void returns404UserNotFound() {
            UserNotFoundException ex = new UserNotFoundException("User not found");

            ResponseEntity<ApiError> response = sut.handleUserNotFoundException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("USER_NOT_FOUND");
        }
    }

    // ---------------------------------------------------------------
    // handleRoleAssignmentNotFoundException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleRoleAssignmentNotFoundException")
    class HandleRoleAssignmentNotFoundException {

        @Test
        @DisplayName("returns 404 ROLE_ASSIGNMENT_NOT_FOUND")
        void returns404RoleAssignmentNotFound() {
            RoleAssignmentNotFoundException ex = new RoleAssignmentNotFoundException("Assignment not found");

            ResponseEntity<ApiError> response = sut.handleRoleAssignmentNotFoundException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("ROLE_ASSIGNMENT_NOT_FOUND");
        }
    }

    // ---------------------------------------------------------------
    // handlePermissionNotFoundException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handlePermissionNotFoundException")
    class HandlePermissionNotFoundException {

        @Test
        @DisplayName("returns 404 PERMISSION_NOT_FOUND")
        void returns404PermissionNotFound() {
            PermissionNotFoundException ex = new PermissionNotFoundException("Permission not found");

            ResponseEntity<ApiError> response = sut.handlePermissionNotFoundException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("PERMISSION_NOT_FOUND");
        }
    }

    // ---------------------------------------------------------------
    // handleIllegalArgumentException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleIllegalArgumentException")
    class HandleIllegalArgumentException {

        @Test
        @DisplayName("returns 400 INVALID_REQUEST with message when present")
        void returns400WithMessage() {
            IllegalArgumentException ex = new IllegalArgumentException("bad param");

            ResponseEntity<ApiError> response = sut.handleIllegalArgumentException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
            assertThat(response.getBody().message()).isEqualTo("bad param");
        }

        @Test
        @DisplayName("returns 400 INVALID_REQUEST with default message when null")
        void returns400WithDefaultMessageWhenNull() {
            IllegalArgumentException ex = new IllegalArgumentException((String) null);

            ResponseEntity<ApiError> response = sut.handleIllegalArgumentException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
            assertThat(response.getBody().message()).isEqualTo("Invalid request parameters");
        }
    }

    // ---------------------------------------------------------------
    // handleBadRequestExceptions
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleBadRequestExceptions")
    class HandleBadRequestExceptions {

        @Test
        @DisplayName("returns 400 INVALID_REQUEST for HttpMessageNotReadableException")
        void returns400ForHttpMessageNotReadable() {
            HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
            when(ex.getMessage()).thenReturn("JSON parse error");

            ResponseEntity<ApiError> response = sut.handleBadRequestExceptions(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
            assertThat(response.getBody().message()).isEqualTo("Invalid request parameters");
        }

        @Test
        @DisplayName("returns 400 INVALID_REQUEST for MissingServletRequestParameterException")
        void returns400ForMissingParameter() {
            MissingServletRequestParameterException ex = new MissingServletRequestParameterException("id", "String");

            ResponseEntity<ApiError> response = sut.handleBadRequestExceptions(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
        }
    }

    // ---------------------------------------------------------------
    // handleAuthorizationDeniedException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleAuthorizationDeniedException")
    class HandleAuthorizationDeniedException {

        @Test
        @DisplayName("returns 403 FORBIDDEN when audit service is unavailable")
        void returns403WithNullAuditService() {
            AuthorizationDeniedException ex = new AuthorizationDeniedException("denied");
            HttpServletRequest httpReq = mock(HttpServletRequest.class);
            when(httpReq.getRequestURI()).thenReturn("/v1/resource");
            when(auditEventServiceProvider.getIfAvailable()).thenReturn(null);

            ResponseEntity<ApiError> response =
                    sut.handleAuthorizationDeniedException(ex, requestWithHeader(), httpReq);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
        }

        @Test
        @DisplayName("returns 403 FORBIDDEN and emits audit event when service is available")
        void returns403AndEmitsAuditEventWhenServiceAvailable() {
            AuthorizationDeniedException ex = new AuthorizationDeniedException("access denied to resource");
            HttpServletRequest httpReq = mock(HttpServletRequest.class);
            when(httpReq.getRequestURI()).thenReturn("/v1/orders");
            AuditEventService auditService = mock(AuditEventService.class);
            when(auditEventServiceProvider.getIfAvailable()).thenReturn(auditService);

            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(auth.getName()).thenReturn("alice");
            SecurityContextHolder.getContext().setAuthentication(auth);

            ResponseEntity<ApiError> response =
                    sut.handleAuthorizationDeniedException(ex, requestWithHeader(), httpReq);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
        }

        @Test
        @DisplayName("returns 403 FORBIDDEN and logs warning when audit event creation throws")
        void returns403WhenAuditEventThrows() {
            AuthorizationDeniedException ex = new AuthorizationDeniedException("denied");
            HttpServletRequest httpReq = mock(HttpServletRequest.class);
            when(httpReq.getRequestURI()).thenReturn("/v1/resource");
            AuditEventService auditService = mock(AuditEventService.class);
            when(auditEventServiceProvider.getIfAvailable()).thenReturn(auditService);
            doThrow(new RuntimeException("Audit DB unavailable"))
                    .when(auditService)
                    .createEvent(any());

            ResponseEntity<ApiError> response =
                    sut.handleAuthorizationDeniedException(ex, requestWithHeader(), httpReq);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
        }

        @Test
        @DisplayName("returns 403 FORBIDDEN with null httpServletRequest (falls back to 'unknown' URI)")
        void returns403WithNullHttpServletRequest() {
            AuthorizationDeniedException ex = new AuthorizationDeniedException("denied");
            when(auditEventServiceProvider.getIfAvailable()).thenReturn(null);

            ResponseEntity<ApiError> response = sut.handleAuthorizationDeniedException(ex, requestWithHeader(), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("returns 403 FORBIDDEN with null exception message (falls back to 'unknown')")
        void returns403WhenExceptionMessageIsNull() {
            AuthorizationDeniedException ex = mock(AuthorizationDeniedException.class);
            when(ex.getMessage()).thenReturn(null);
            HttpServletRequest httpReq = mock(HttpServletRequest.class);
            when(httpReq.getRequestURI()).thenReturn("/v1/resource");
            when(auditEventServiceProvider.getIfAvailable()).thenReturn(null);

            ResponseEntity<ApiError> response =
                    sut.handleAuthorizationDeniedException(ex, requestWithHeader(), httpReq);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ---------------------------------------------------------------
    // handleIllegalStateException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleIllegalStateException")
    class HandleIllegalStateException {

        @Test
        @DisplayName("returns 409 ROLE_ASSIGNMENT_CONFLICT when message contains 'Overlapping role assignment'")
        void returns409ForOverlappingRoleAssignment() {
            IllegalStateException ex = new IllegalStateException("Overlapping role assignment detected");

            ResponseEntity<ApiError> response = sut.handleIllegalStateException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("ROLE_ASSIGNMENT_CONFLICT");
        }

        @Test
        @DisplayName("returns 400 INVALID_STATE for non-overlap illegal state")
        void returns400ForOtherIllegalState() {
            IllegalStateException ex = new IllegalStateException("Some other invalid state");

            ResponseEntity<ApiError> response = sut.handleIllegalStateException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("INVALID_STATE");
            assertThat(response.getBody().message()).isEqualTo("Some other invalid state");
        }

        @Test
        @DisplayName("returns 400 INVALID_STATE with default message when exception message is null")
        void returns400WithDefaultMessageWhenNull() {
            IllegalStateException ex = new IllegalStateException((String) null);

            ResponseEntity<ApiError> response = sut.handleIllegalStateException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("INVALID_STATE");
            assertThat(response.getBody().message()).isEqualTo("Invalid state");
        }
    }

    // ---------------------------------------------------------------
    // handleOptimisticLockingFailure
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleOptimisticLockingFailure")
    class HandleOptimisticLockingFailure {

        @Test
        @DisplayName("returns 409 CONCURRENCY_CONFLICT")
        void returns409ConcurrencyConflict() {
            ObjectOptimisticLockingFailureException ex =
                    new ObjectOptimisticLockingFailureException(Object.class, "id-123");

            ResponseEntity<ApiError> response = sut.handleOptimisticLockingFailure(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("CONCURRENCY_CONFLICT");
        }
    }

    // ---------------------------------------------------------------
    // handleDuplicateRoleNameException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleDuplicateRoleNameException")
    class HandleDuplicateRoleNameException {

        @Test
        @DisplayName("returns 409 DUPLICATE_ROLE_NAME")
        void returns409DuplicateRoleName() {
            DuplicateRoleNameException ex = new DuplicateRoleNameException("Role 'ADMIN' already exists");

            ResponseEntity<ApiError> response = sut.handleDuplicateRoleNameException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("DUPLICATE_ROLE_NAME");
        }
    }

    // ---------------------------------------------------------------
    // handleGenericException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleGenericException")
    class HandleGenericException {

        @Test
        @DisplayName("returns 500 INTERNAL_SERVER_ERROR for unhandled exceptions")
        void returns500InternalServerError() {
            Exception ex = new RuntimeException("unexpected failure");

            ResponseEntity<ApiError> response = sut.handleGenericException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("INTERNAL_SERVER_ERROR");
            assertThat(response.getBody().correlationId()).isEqualTo(CORRELATION_ID);
        }

        @Test
        @DisplayName("returns 500 and generates correlation ID when header is absent")
        void returns500WithGeneratedCorrelationId() {
            Exception ex = new RuntimeException("unexpected");

            ResponseEntity<ApiError> response = sut.handleGenericException(ex, requestWithoutHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }
    }

    // ---------------------------------------------------------------
    // getCurrentUsername — via handleAuthorizationDeniedException
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("getCurrentUsername — via SecurityContextHolder")
    class GetCurrentUsername {

        @Test
        @DisplayName("returns authenticated username when security context has an authenticated principal")
        void returnsUsernameWhenAuthenticated() {
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(auth.getName()).thenReturn("bob");
            SecurityContextHolder.getContext().setAuthentication(auth);

            AuthorizationDeniedException ex = new AuthorizationDeniedException("denied");
            HttpServletRequest httpReq = mock(HttpServletRequest.class);
            when(httpReq.getRequestURI()).thenReturn("/v1/thing");
            when(auditEventServiceProvider.getIfAvailable()).thenReturn(null);

            ResponseEntity<ApiError> response =
                    sut.handleAuthorizationDeniedException(ex, requestWithHeader(), httpReq);

            // Handler completed successfully — username was resolved from context
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("returns 'system' fallback when security context has no authentication")
        void returnsSystemWhenNoAuthentication() {
            SecurityContextHolder.clearContext(); // ensure no auth

            AuthorizationDeniedException ex = new AuthorizationDeniedException("denied");
            HttpServletRequest httpReq = mock(HttpServletRequest.class);
            when(httpReq.getRequestURI()).thenReturn("/v1/thing");
            when(auditEventServiceProvider.getIfAvailable()).thenReturn(null);

            ResponseEntity<ApiError> response =
                    sut.handleAuthorizationDeniedException(ex, requestWithHeader(), httpReq);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ---------------------------------------------------------------
    // getCurrentUsername — additional branch: authenticated=false
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("getCurrentUsername — non-authenticated principal")
    class GetCurrentUsernameNotAuthenticated {

        @Test
        @DisplayName("returns 'system' when authentication is present but isAuthenticated() is false")
        void returnsSystemWhenNotAuthenticated() {
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(false);
            SecurityContextHolder.getContext().setAuthentication(auth);

            AuthorizationDeniedException ex = new AuthorizationDeniedException("denied");
            HttpServletRequest httpReq = mock(HttpServletRequest.class);
            when(httpReq.getRequestURI()).thenReturn("/v1/resource");
            when(auditEventServiceProvider.getIfAvailable()).thenReturn(null);

            ResponseEntity<ApiError> response =
                    sut.handleAuthorizationDeniedException(ex, requestWithHeader(), httpReq);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ---------------------------------------------------------------
    // handleAuthorizationDeniedException — null provider branch
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleAuthorizationDeniedException — null ObjectProvider")
    class HandleAuthorizationDeniedNullProvider {

        @Test
        @DisplayName("returns 403 FORBIDDEN when ObjectProvider itself is null (direct instantiation)")
        void returns403WhenObjectProviderIsNull() {
            GlobalExceptionHandler handlerWithNullProvider = new GlobalExceptionHandler(
                    Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC), null);

            AuthorizationDeniedException ex = new AuthorizationDeniedException("denied");
            HttpServletRequest httpReq = mock(HttpServletRequest.class);
            when(httpReq.getRequestURI()).thenReturn("/v1/resource");
            WebRequest req = mock(WebRequest.class);
            when(req.getHeader("X-Correlation-Id")).thenReturn(CORRELATION_ID);

            ResponseEntity<ApiError> response =
                    handlerWithNullProvider.handleAuthorizationDeniedException(ex, req, httpReq);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
        }
    }

    // ---------------------------------------------------------------
    // AUTH-004: account-state denial mappings
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handleDisabledException — AUTH-004")
    class HandleDisabledException {

        @Test
        @DisplayName("returns 401 ACCOUNT_DISABLED with correlation ID from header")
        void returns401WithCorrelationIdFromHeader() {
            DisabledException ex = new DisabledException("Account is disabled");

            ResponseEntity<ApiError> response = sut.handleDisabledException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isEqualTo("ACCOUNT_DISABLED");
            assertThat(response.getBody().correlationId()).isEqualTo(CORRELATION_ID);
        }

        @Test
        @DisplayName("generates correlation ID when header is absent")
        void generatesCorrelationIdWhenHeaderAbsent() {
            DisabledException ex = new DisabledException("Account is disabled");

            ResponseEntity<ApiError> response = sut.handleDisabledException(ex, requestWithoutHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isEqualTo("ACCOUNT_DISABLED");
            assertThat(response.getBody().correlationId()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("handleAccountExpiredException — AUTH-004")
    class HandleAccountExpiredException {

        @Test
        @DisplayName("returns 401 ACCOUNT_EXPIRED with correlation ID from header")
        void returns401WithCorrelationIdFromHeader() {
            AccountExpiredException ex = new AccountExpiredException("Account has expired");

            ResponseEntity<ApiError> response = sut.handleAccountExpiredException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isEqualTo("ACCOUNT_EXPIRED");
            assertThat(response.getBody().correlationId()).isEqualTo(CORRELATION_ID);
        }

        @Test
        @DisplayName("generates correlation ID when header is absent")
        void generatesCorrelationIdWhenHeaderAbsent() {
            AccountExpiredException ex = new AccountExpiredException("Account has expired");

            ResponseEntity<ApiError> response = sut.handleAccountExpiredException(ex, requestWithoutHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isEqualTo("ACCOUNT_EXPIRED");
            assertThat(response.getBody().correlationId()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("handleCredentialsExpiredException — AUTH-004")
    class HandleCredentialsExpiredException {

        @Test
        @DisplayName("returns 401 CREDENTIALS_EXPIRED with correlation ID from header")
        void returns401WithCorrelationIdFromHeader() {
            CredentialsExpiredException ex = new CredentialsExpiredException("Credentials have expired");

            ResponseEntity<ApiError> response = sut.handleCredentialsExpiredException(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isEqualTo("CREDENTIALS_EXPIRED");
            assertThat(response.getBody().correlationId()).isEqualTo(CORRELATION_ID);
        }

        @Test
        @DisplayName("generates correlation ID when header is absent")
        void generatesCorrelationIdWhenHeaderAbsent() {
            CredentialsExpiredException ex = new CredentialsExpiredException("Credentials have expired");

            ResponseEntity<ApiError> response = sut.handleCredentialsExpiredException(ex, requestWithoutHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isEqualTo("CREDENTIALS_EXPIRED");
            assertThat(response.getBody().correlationId()).isNotBlank();
        }
    }
}
