package com.positivity.securityservice.internal.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.dto.ErrorResponse;
import com.positivity.securityservice.service.AuditEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a JSON {@link ErrorResponse} body and persists a PermissionDenied
 * audit
 * event when Spring Security's ExceptionTranslationFilter intercepts an
 * {@link AccessDeniedException} before Spring MVC's {@code @ControllerAdvice}.
 *
 * <p>
 * Issue: AUTH-009 (regression fix — access-denied response body + audit event)
 */
@Slf4j
@Component
@RequiredArgsConstructor
class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ObjectProvider<AuditEventService> auditEventServiceProvider;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {

        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        if (accessDeniedException instanceof AuthorizationDeniedException) {
            persistPermissionDeniedAuditEvent(
                    correlationId, request, accessDeniedException.getMessage());
        }

        ErrorResponse body = new ErrorResponse(
                "FORBIDDEN", "Access denied",
                HttpServletResponse.SC_FORBIDDEN, Instant.now(clock).toString(), correlationId, null, null, null);

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }

    private void persistPermissionDeniedAuditEvent(
            @NonNull String correlationId,
            @NonNull HttpServletRequest request,
            String deniedPermissions) {
        try {
            AuditEventService auditEventService = auditEventServiceProvider.getIfAvailable();
            if (auditEventService == null) {
                return;
            }
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String actorId = auth != null ? auth.getName() : "system";
            String resourceUri = request.getRequestURI();
            String message = deniedPermissions != null ? deniedPermissions : "unknown";
            auditEventService.createEvent(new AuditLogEventRequest(
                    "PermissionDenied", actorId, resourceUri, "Authorization", "", "",
                    Map.of(
                            "message", message,
                            "requestUri", resourceUri,
                            "deniedPermissions", message)));
        } catch (Exception e) {
            log.warn("Failed to persist PermissionDenied audit event (correlationId={}): {}",
                    correlationId, e.getMessage());
        }
    }
}
