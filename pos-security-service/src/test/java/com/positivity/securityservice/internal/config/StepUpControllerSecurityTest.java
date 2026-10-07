package com.positivity.securityservice.internal.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.controller.StepUpController;
import com.positivity.securityservice.internal.dto.StepUpResponse;
import com.positivity.securityservice.internal.exception.StepUpDeniedException;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.security.PermissionRegistrationSecretFilter;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.internal.service.StepUpService;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CAP:550 S16 (#2512, review B1/M2): the step-up runs through the REAL {@link SecurityConfig}. Its
 * {@code /internal/**} chain has no CSRF — pos-order's POST carries no token — and accepts only the mesh
 * service credential, never gateway identity headers.
 */
@DisplayName("POST /internal/v1/auth/step-up through the real security chain")
@WebMvcTest(StepUpController.class)
@Import({
    SecurityConfig.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    PermissionRegistrationSecretFilter.class,
    StepUpControllerSecurityTest.SliceConfig.class
})
@TestPropertySource(properties = "pos.security.api-secret=test-internal-secret")
class StepUpControllerSecurityTest {

    private static final String PATH = "/internal/v1/auth/step-up";
    private static final String SECRET_HEADER = "X-Internal-Api-Secret";
    private static final String BODY = """
            {"username":"manager","password":"s3cret","permission":"order:session:approve_cash_movement",
             "locationId":"01900000-0000-7000-8000-0000000000d1"}
            """;
    private static final UUID MANAGER_ID = UUID.fromString("01900000-0000-7000-8000-0000000000b1");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StepUpService stepUpService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @BeforeEach
    void jwtFilterPassesThrough() throws Exception {
        doAnswer(inv -> {
                    ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .when(jwtAuthenticationFilter)
                .doFilter(any(), any(), any());
    }

    @Test
    @DisplayName("B1: a POST with the service credential and no CSRF token reaches the check — 200")
    void serviceCredentialWithoutCsrfIsAccepted() throws Exception {
        when(stepUpService.verify(eq("manager"), eq("s3cret"), eq("order:session:approve_cash_movement"), any()))
                .thenReturn(new StepUpResponse(MANAGER_ID, true, false, false, List.of()));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SECRET_HEADER, "test-internal-secret")
                        .header("X-Tenant-Id", "01900000-0000-7000-8000-000000000001")
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(MANAGER_ID.toString()))
                .andExpect(jsonPath("$.holdsPermission").value(true));
    }

    @Test
    @DisplayName("B1: a refused check answers 403 STEP_UP_DENIED through the real chain, never 401")
    void deniedCheckIs403() throws Exception {
        when(stepUpService.verify(any(), any(), any(), any())).thenThrow(new StepUpDeniedException("bad_credentials"));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SECRET_HEADER, "test-internal-secret")
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STEP_UP_DENIED"));
    }

    @Test
    @DisplayName("M2: no service credential, or gateway identity headers instead of it, never reach the check")
    void gatewayHeadersOrNoCredentialAreRefused() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_SECRET"));
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User", "cashier")
                        .header("X-Authorities", "order:session:cash_movement,security:token:issue_internal")
                        .content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SECRET_HEADER, "wrong-secret")
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verify(stepUpService, never()).verify(any(), any(), any(), any());
    }

    @TestConfiguration
    static class SliceConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
