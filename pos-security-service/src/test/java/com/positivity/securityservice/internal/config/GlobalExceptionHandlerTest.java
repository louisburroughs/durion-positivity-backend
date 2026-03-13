package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

import com.positivity.securityservice.internal.dto.ErrorResponse;
import com.positivity.securityservice.internal.exception.InvalidRefreshTokenException;
import com.positivity.securityservice.service.AuditEventService;

/**
 * Unit tests for GlobalExceptionHandler PERM-004 changes.
 *
 * <p>
 * Covers the {@code handleInvalidRefreshTokenException} handler added as part
 * of
 * PERM-004 (compact permission bitset encoding).
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String CORRELATION_ID = "test-correlation-id";

    @Mock
    Clock clock;

    @SuppressWarnings("unchecked")
    @Mock
    ObjectProvider<AuditEventService> auditEventServiceProvider;

    @InjectMocks
    GlobalExceptionHandler sut;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(TEST_CLOCK.instant());
        org.mockito.Mockito.lenient().when(clock.getZone()).thenReturn(TEST_CLOCK.getZone());
    }

    /**
     * Verifies that {@code handleInvalidRefreshTokenException} maps
     * {@link InvalidRefreshTokenException} to 401 Unauthorized with the
     * {@code INVALID_REFRESH_TOKEN} error code.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("handleInvalidRefreshTokenException returns 401 Unauthorized with INVALID_REFRESH_TOKEN code")
    void handleInvalidRefreshTokenException_returns401WithCorrectErrorCode() {
        InvalidRefreshTokenException ex = new InvalidRefreshTokenException(
                "Refresh token references a user that no longer exists");
        WebRequest request = mock(WebRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(CORRELATION_ID);

        ResponseEntity<ErrorResponse> response = sut.handleInvalidRefreshTokenException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(response.getBody().message()).isEqualTo(
                "Refresh token references a user that no longer exists");
        assertThat(response.getBody().status()).isEqualTo(401);
        assertThat(response.getBody().correlationId()).isEqualTo(CORRELATION_ID);
    }

    /**
     * Verifies that {@code handleInvalidRefreshTokenException} generates a
     * correlation ID when no {@code X-Correlation-Id} header is present.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("handleInvalidRefreshTokenException generates correlation ID when header is absent")
    void handleInvalidRefreshTokenException_generatesCorrelationIdWhenHeaderAbsent() {
        InvalidRefreshTokenException ex = new InvalidRefreshTokenException("user gone");
        WebRequest request = mock(WebRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(null);

        ResponseEntity<ErrorResponse> response = sut.handleInvalidRefreshTokenException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(response.getBody().correlationId()).isNotBlank();
    }
}
