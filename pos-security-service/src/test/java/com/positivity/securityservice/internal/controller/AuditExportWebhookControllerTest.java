package com.positivity.securityservice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.dto.AuditExportWebhookRegistrationResponse;
import com.positivity.securityservice.internal.dto.AuditExportWebhookResponse;
import com.positivity.securityservice.internal.exception.AuditExportWebhookInvalidException;
import com.positivity.securityservice.internal.exception.AuditExportWebhookNotRegisteredException;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.service.AuditExportWebhookService;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * #2497: {@code /v1/audit/exports/webhook} — who may register, read and remove the tenant's audit
 * export webhook, the secret shown by the registration alone, and the error codes.
 */
@WebMvcTest(AuditExportWebhookController.class)
@DisplayName("AuditExportWebhookController - the tenant's audit export webhook (#2497)")
class AuditExportWebhookControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-10-12T09:30:00Z"), ZoneOffset.UTC);
    private static final String PATH = "/v1/audit/exports/webhook";
    private static final String MANAGE = "security:audit_export:webhook_manage";
    private static final String EXPORT = "security:audit:export";
    private static final String URL = "https://siem.example.com/hooks/audit";
    private static final String SECRET = "example-only-not-a-real-secret-00000000000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditExportWebhookService webhookService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @BeforeEach
    void configureJwtFilterPassthrough() throws Exception {
        doAnswer(inv -> {
                    ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .when(jwtAuthenticationFilter)
                .doFilter(any(), any(), any());
    }

    private static AuditExportWebhookResponse destination() {
        return AuditExportWebhookResponse.builder()
                .url(URL)
                .registeredAt(Instant.parse("2026-10-12T09:00:00Z"))
                .registeredBy("admin.alpha")
                .build();
    }

    @Test
    @WithMockUser(authorities = MANAGE)
    @DisplayName("PUT with webhook_manage → 200 with the destination and its signing secret")
    void registerShowsSecret() throws Exception {
        when(webhookService.register(any()))
                .thenReturn(AuditExportWebhookRegistrationResponse.builder()
                        .url(URL)
                        .registeredAt(Instant.parse("2026-10-12T09:30:00Z"))
                        .registeredBy("admin.alpha")
                        .signingSecret(SECRET)
                        .build());

        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.registeredBy").value("admin.alpha"))
                .andExpect(jsonPath("$.signingSecret").value(SECRET));
    }

    @Test
    @WithMockUser(authorities = EXPORT)
    @DisplayName("PUT with only security:audit:export → 403: requesting exports is not choosing where they go")
    void registerNeedsManage() throws Exception {
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isForbidden());
        verify(webhookService, never()).register(any());
    }

    @Test
    @WithMockUser(authorities = MANAGE)
    @DisplayName("PUT with a refused URL → 400 VALIDATION_ERROR with fieldErrors on url")
    void registerInvalid() throws Exception {
        when(webhookService.register(any()))
                .thenThrow(new AuditExportWebhookInvalidException(List.of("url must use https")));

        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://x.example\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("url"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("url must use https"));
    }

    @Test
    @WithMockUser(authorities = EXPORT)
    @DisplayName("GET with security:audit:export → 200 with the destination and no secret")
    void getWithExport() throws Exception {
        when(webhookService.get()).thenReturn(destination());

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.registeredBy").value("admin.alpha"))
                .andExpect(jsonPath("$.signingSecret").doesNotExist());
    }

    @Test
    @WithMockUser(authorities = MANAGE)
    @DisplayName("GET with webhook_manage → 200")
    void getWithManage() throws Exception {
        when(webhookService.get()).thenReturn(destination());

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(URL));
    }

    @Test
    @WithMockUser(authorities = "security:audit:view")
    @DisplayName("GET with neither authority → 403")
    void getNeedsEither() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
        verify(webhookService, never()).get();
    }

    @Test
    @WithMockUser(authorities = EXPORT)
    @DisplayName("GET with nothing registered → 404 AUDIT_EXPORT_WEBHOOK_NOT_REGISTERED")
    void getUnregistered() throws Exception {
        when(webhookService.get()).thenThrow(AuditExportWebhookNotRegisteredException.forLookup());

        mockMvc.perform(get(PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUDIT_EXPORT_WEBHOOK_NOT_REGISTERED"));
    }

    @Test
    @WithMockUser(authorities = MANAGE)
    @DisplayName("DELETE with webhook_manage → 204, and again → 204 (idempotent)")
    void deleteIdempotent() throws Exception {
        mockMvc.perform(delete(PATH)).andExpect(status().isNoContent());
        mockMvc.perform(delete(PATH)).andExpect(status().isNoContent());

        verify(webhookService, times(2)).delete();
    }

    @Test
    @WithMockUser(authorities = EXPORT)
    @DisplayName("DELETE with only security:audit:export → 403")
    void deleteNeedsManage() throws Exception {
        mockMvc.perform(delete(PATH)).andExpect(status().isForbidden());
        verify(webhookService, never()).delete();
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    /** Maps Spring Security exceptions to 401/403 in this slice, ahead of GlobalExceptionHandler. */
    @ControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class SecurityExceptionControllerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
            // Intentionally empty: @ResponseStatus drives the HTTP mapping in this slice.
        }

        @ExceptionHandler(AuthenticationException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        void handleUnauthenticated() {
            // Intentionally empty: @ResponseStatus drives the HTTP mapping in this slice.
        }
    }
}
