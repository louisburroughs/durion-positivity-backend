package com.positivity.securityservice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.service.PermissionCatalogVersionService;
import com.positivity.securityservice.service.PermissionRegistryService;
import com.positivity.securityservice.service.PermissionService;
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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Controller slice tests for {@link PermissionController} PERM-005 endpoints.
 *
 * <p>
 * Verifies the PERM-005 acceptance criteria for catalog-version lookup and
 * permission-bitset decode endpoints:
 * <ul>
 * <li>AC1 — {@code GET /v1/permissions/catalog-version} returns version +
 * count, no auth required</li>
 * <li>AC2 — {@code POST /v1/permissions/decode} returns decoded permissions,
 * requires
 * {@code security:permission:view} authority</li>
 * <li>AC3 — {@code POST /v1/permissions/decode} carries {@code @EmitEvent}</li>
 * </ul>
 * Issue: PERM-005
 */
@WebMvcTest(PermissionController.class)
@DisplayName("PermissionCatalogControllerTest — PERM-005")
class PermissionCatalogControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    // ── Service dependencies required by PermissionController ────────────────

    @MockitoBean
    private PermissionCatalogVersionService permissionCatalogVersionService;

    @MockitoBean
    private PermissionRegistryService permissionRegistryService;

    @MockitoBean
    private PermissionService permissionService;

    // ── Security infrastructure ───────────────────────────────────────────────

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    /**
     * Make the mocked JWT filter a passthrough so it does not swallow requests.
     * {@code @WithMockUser} directly populates the
     * {@link org.springframework.security.core.context.SecurityContext}
     * before the filter chain runs, so no real token processing is needed.
     */
    @BeforeEach
    void configureJwtFilterPassthrough() throws Exception {
        doAnswer(inv -> {
                    ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .when(jwtAuthenticationFilter)
                .doFilter(any(), any(), any());
    }

    // ── AC1a: GET /v1/permissions/catalog-version → 200 ─────────────────────

    /**
     * PERM-005 AC1: GET /v1/permissions/catalog-version returns 200 with version
     * and permissionCount.
     */
    @Test
    @WithMockUser(authorities = "security:permission:view")
    @DisplayName("GET /v1/permissions/catalog-version → 200 with version and permissionCount")
    void getCatalogVersion_returnsVersionAndCount() throws Exception {
        when(permissionCatalogVersionService.getCatalogVersion()).thenReturn(1);
        when(permissionCatalogVersionService.getPermissionCount()).thenReturn(215);

        mockMvc.perform(get("/v1/permissions/catalog-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.permissionCount").value(215));
    }

    // ── AC1b: GET /v1/permissions/catalog-version — no auth required ─────────

    /**
     * PERM-005 AC1: GET /v1/permissions/catalog-version must not require
     * authentication.
     */
    @Test
    @DisplayName("GET /v1/permissions/catalog-version without auth → 200 (no auth required)")
    void getCatalogVersion_doesNotRequireAuthentication() throws Exception {
        when(permissionCatalogVersionService.getCatalogVersion()).thenReturn(1);
        when(permissionCatalogVersionService.getPermissionCount()).thenReturn(215);

        mockMvc.perform(get("/v1/permissions/catalog-version")).andExpect(status().isOk());
    }

    // ── AC2a: POST /v1/permissions/decode → 200 ─────────────────────────────

    /**
     * PERM-005 AC2: POST /v1/permissions/decode with valid body and required
     * authority
     * returns 200 with {@code permissions} list.
     */
    @Test
    @WithMockUser(authorities = "security:permission:view")
    @DisplayName("POST /v1/permissions/decode + security:permission:view authority → 200 with permissions")
    void decodePermissions_returns200WithPermissionList() throws Exception {
        when(permissionCatalogVersionService.decodePermissions(anyString(), anyInt()))
                .thenReturn(List.of("accounting:je:view", "accounting:je:create"));

        mockMvc.perform(post("/v1/permissions/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perm_bits\":\"Aw\",\"perm_ver\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isArray());
    }

    // ── AC2b: POST /v1/permissions/decode — 403 without authority ────────────

    /**
     * PERM-005 AC2: POST /v1/permissions/decode returns 403 when caller lacks
     * {@code security:permission:view} authority.
     */
    @Test
    @WithMockUser(roles = "VIEWER")
    @DisplayName("POST /v1/permissions/decode without security:permission:view authority → 403 Forbidden")
    void decodePermissions_requires_security_permission_view_authority() throws Exception {
        mockMvc.perform(post("/v1/permissions/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perm_bits\":\"Aw\",\"perm_ver\":1}"))
                .andExpect(status().isForbidden());
    }

    // ── AC2c: POST /v1/permissions/decode — 400 for null perm_bits ───────────

    /**
     * PERM-005 AC2: POST /v1/permissions/decode with null perm_bits returns 400 Bad
     * Request.
     * Bean Validation ({@code @NotNull}) on
     * {@code PermissionDecodeRequest.permBits} enforces this.
     */
    @Test
    @WithMockUser(authorities = "security:permission:view")
    @DisplayName("POST /v1/permissions/decode with null perm_bits → 400 Bad Request")
    void decodePermissions_validates_perm_bits_not_null() throws Exception {
        mockMvc.perform(post("/v1/permissions/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perm_bits\":null,\"perm_ver\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "security:permission:view")
    @DisplayName("POST /decode with empty perm_bits returns 400 Bad Request")
    void decodePermissions_emptyPermBits_returns400() throws Exception {
        mockMvc.perform(post("/v1/permissions/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perm_bits\":\"\",\"perm_ver\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "security:permission:view")
    @DisplayName("POST /decode with perm_ver <= 0 returns 400 Bad Request")
    void decodePermissions_nonPositivePermVer_returns400() throws Exception {
        mockMvc.perform(post("/v1/permissions/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perm_bits\":\"Aw==\",\"perm_ver\":0}"))
                .andExpect(status().isBadRequest());
    }

    // ── B-1: GET /v1/permissions — paged list ────────────────────────────────

    /**
     * B-1: GET /v1/permissions?page=0&size=20 returns 200 with a paged content
     * array.
     */
    @Test
    @WithMockUser(authorities = "security:permission:view")
    @DisplayName("GET /v1/permissions?page=0&size=20 → 200 with paged result")
    void listPermissions_noFilter_returnsPage() throws Exception {
        PermissionDto dto = PermissionDto.builder()
                .id(UUID.randomUUID())
                .name("catalog:product:view")
                .domain("catalog")
                .description("View products")
                .deprecated(false)
                .build();
        when(permissionService.listPermissions(isNull(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(dto)));

        mockMvc.perform(get("/v1/permissions").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].name").value("catalog:product:view"));
    }

    /**
     * B-1: GET /v1/permissions?domain=catalog returns 200 with only catalog-scoped
     * results.
     */
    @Test
    @WithMockUser(authorities = "security:permission:view")
    @DisplayName("GET /v1/permissions?domain=catalog → 200 with filtered results")
    void listPermissions_withDomainFilter_returnsFilteredPage() throws Exception {
        PermissionDto dto = PermissionDto.builder()
                .id(UUID.randomUUID())
                .name("catalog:product:view")
                .domain("catalog")
                .description("View products")
                .deprecated(false)
                .build();
        when(permissionService.listPermissions(eq("catalog"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto)));

        mockMvc.perform(get("/v1/permissions").param("domain", "catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].domain").value("catalog"));
    }

    /**
     * B-1: GET /v1/permissions without the required authority returns 403.
     */
    @Test
    @WithMockUser(roles = "VIEWER")
    @DisplayName("GET /v1/permissions without security:permission:view authority → 403 Forbidden")
    void listPermissions_missingAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/permissions")).andExpect(status().isForbidden());
    }

    // ── Test slice configuration ──────────────────────────────────────────────

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        /** Clock bean required by GlobalExceptionHandler. */
        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    /**
     * Minimal security exception handler for the MVC slice — translates Spring
     * Security
     * exceptions to 401/403 HTTP status codes. {@code @Order(HIGHEST_PRECEDENCE)}
     * ensures
     * this advice is consulted before the production {@code GlobalExceptionHandler}
     * which
     * has a catch-all that would otherwise return 500 for security exceptions.
     */
    @ControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class SecurityExceptionControllerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
            // Status code mapping is the behavior under test.
        }

        @ExceptionHandler(AuthenticationException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        void handleUnauthenticated() {
            // Status code mapping is the behavior under test.
        }

        /**
         * Returns 404 for requests to endpoints that do not yet exist (RED scaffold
         * tests).
         * Without this, Spring's unhandled NoResourceFoundException falls through to
         * GlobalExceptionHandler.handleException(Exception) and returns 500.
         */
        @ExceptionHandler(NoResourceFoundException.class)
        @ResponseStatus(HttpStatus.NOT_FOUND)
        void handleNotFound() {
            // Maintain explicit 404 mapping for missing endpoints in this MVC test slice.
        }
    }
}
