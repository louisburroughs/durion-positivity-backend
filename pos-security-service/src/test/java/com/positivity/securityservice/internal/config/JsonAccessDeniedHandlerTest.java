package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.service.AuditEventService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Access-denied responses (AUTH-009): Spring Security's ExceptionTranslationFilter runs before
 * the controller advice, so this handler owns the 403 body and the PermissionDenied audit event.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JsonAccessDeniedHandler")
class JsonAccessDeniedHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final String URI = "/v1/users/00000000-0000-0000-0000-000000000001";

    @Mock
    private AuditEventService auditEventService;

    @Mock
    private ObjectProvider<AuditEventService> auditEventServiceProvider;

    private JsonAccessDeniedHandler handler;

    @BeforeEach
    void setUp() {
        when(auditEventServiceProvider.getIfAvailable()).thenReturn(auditEventService);
        handler = new JsonAccessDeniedHandler(
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), auditEventServiceProvider);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", URI);
        request.setRequestURI(URI);
        return request;
    }

    private static void authenticateAs(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private static AuthorizationDeniedException authorizationDenied() {
        return new AuthorizationDeniedException("Access Denied", (AuthorizationResult) () -> false);
    }

    @Test
    void writesTheForbiddenEnvelopeWithoutLeakingTheDeniedRule() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request(), response, new AccessDeniedException("needs security:user:delete"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).contains("FORBIDDEN").contains("Access denied");
        assertThat(response.getContentAsString()).contains(NOW.toString());
        assertThat(response.getContentAsString()).doesNotContain("security:user:delete");
    }

    @Test
    void keepsACallerSuppliedCorrelationId() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-Correlation-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getContentAsString()).contains("abc-123");
    }

    @Test
    void mintsACorrelationIdWhenTheCallerSendsNone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request(), response, new AccessDeniedException("denied"));

        assertThat(response.getContentAsString()).contains("correlationId");
    }

    @Test
    void recordsAnAuditEventOnlyForAnAuthorizationDenial() throws Exception {
        authenticateAs("jane.smith");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request(), response, authorizationDenied());

        ArgumentCaptor<AuditLogEventRequest> audit = ArgumentCaptor.forClass(AuditLogEventRequest.class);
        verify(auditEventService).createEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo("PermissionDenied");
        assertThat(audit.getValue().getActorId()).isEqualTo("jane.smith");
        assertThat(audit.getValue().getEntityId()).isEqualTo(URI);
    }

    @Test
    void recordsNoAuditEventForAPlainAccessDenial() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request(), response, new AccessDeniedException("denied"));

        verify(auditEventService, never()).createEvent(any());
    }

    @Test
    void attributesTheAuditEventToTheSystemWhenNobodyIsAuthenticated() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request(), response, authorizationDenied());

        ArgumentCaptor<AuditLogEventRequest> audit = ArgumentCaptor.forClass(AuditLogEventRequest.class);
        verify(auditEventService).createEvent(audit.capture());
        assertThat(audit.getValue().getActorId()).isEqualTo("system");
    }

    @Test
    void stillWritesTheForbiddenBodyWhenNoAuditServiceIsWired() throws Exception {
        when(auditEventServiceProvider.getIfAvailable()).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request(), response, authorizationDenied());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void stillWritesTheForbiddenBodyWhenTheAuditWriteFails() throws Exception {
        doThrow(new IllegalStateException("audit store down"))
                .when(auditEventService)
                .createEvent(any());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request(), response, authorizationDenied());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString()).contains("FORBIDDEN");
    }
}
