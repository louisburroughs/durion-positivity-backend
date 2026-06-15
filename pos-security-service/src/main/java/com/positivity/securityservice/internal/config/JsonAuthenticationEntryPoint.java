package com.positivity.securityservice.internal.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Writes a JSON {@link ErrorResponse} body when Spring Security's
 * {@link org.springframework.security.web.access.ExceptionTranslationFilter}
 * intercepts an {@link AuthenticationException} before Spring MVC's
 * {@code @ControllerAdvice} can handle it.
 *
 * <p>
 * Issue: AUTH-009 (regression fix — authentication error response body)
 */
@Slf4j
@Component
@RequiredArgsConstructor
class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {

        String code;
        String message;
        if (authException instanceof LockedException) {
            code = "ACCOUNT_LOCKED";
            message = "Account is locked";
        } else if (authException instanceof DisabledException) {
            code = "ACCOUNT_DISABLED";
            message = "Account is disabled";
        } else if (authException instanceof AccountExpiredException) {
            code = "ACCOUNT_EXPIRED";
            message = "Account has expired";
        } else if (authException instanceof CredentialsExpiredException) {
            code = "CREDENTIALS_EXPIRED";
            message = "Credentials have expired";
        } else {
            // BadCredentialsException, UsernameNotFoundException (hidden), etc.
            code = "INVALID_CREDENTIALS";
            message = "Invalid username or password";
        }

        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        log.warn(
                "Authentication required (401); uri={} correlationId={} reason={}",
                request.getRequestURI(),
                correlationId,
                code);

        ApiError body = new ApiError(
                code,
                message,
                HttpServletResponse.SC_UNAUTHORIZED,
                Instant.now(clock).toString(),
                correlationId,
                null,
                null,
                null,
                null);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
