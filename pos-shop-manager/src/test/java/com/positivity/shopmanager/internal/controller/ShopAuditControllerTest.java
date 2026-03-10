package com.positivity.shopmanager.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.positivity.shopmanager.internal.dto.ShopAuditEntryResponse;
import com.positivity.shopmanager.internal.enums.ShopAuditEventType;
import com.positivity.shopmanager.service.ShopAuditService;

/**
 * Controller slice tests for Story #61 — Audit Trail for Schedule and
 * Assignment Changes.
 *
 * <p>
 * Tests verify that {@link ShopAuditController} correctly:
 * <ul>
 * <li>Returns 200 with a list for authorized search requests.</li>
 * <li>Returns 400 when no filter criteria are supplied.</li>
 * <li>Returns 200/404 for GET by ID (found/not found).</li>
 * <li>Has no DELETE / PATCH / PUT endpoints (immutability).</li>
 * <li>Returns 401 for unauthenticated callers (ADR-0017).</li>
 * <li>Returns 403 for callers lacking the required authority (ADR-0017).</li>
 * </ul>
 *
 * <p>
 * Structural tests (no DELETE/PATCH/PUT) pass immediately and must remain
 * passing.
 */
@WebMvcTest(ShopAuditController.class)
class ShopAuditControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2025-06-01T10:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShopAuditService shopAuditService;

    // ─── AC: GET /v1/shop/audit — 200 with list ───────────────────────────────

    /**
     * AC: Authorized caller with canonical audit-read authority receives HTTP 200
     * and
     * a JSON array containing matching audit entries.
     */
    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    void searchAudit_withViewAuthorityAndFilter_returns200WithEntryList() throws Exception {
        UUID entryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ShopAuditEntryResponse entry = ShopAuditEntryResponse.builder()
                .id(entryId)
                .eventType(ShopAuditEventType.SCHEDULE_CREATED)
                .workorderId("WO-123")
                .actorUserId("dispatcher@shop.com")
                .recordedAt(Instant.parse("2025-05-01T09:00:00Z"))
                .build();

        when(shopAuditService.search(any())).thenReturn(List.of(entry));

        mockMvc.perform(get("/v1/shop/audit")
                .param("workorderId", "WO-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].workorderId").value("WO-123"))
                .andExpect(jsonPath("$[0].eventType").value("SCHEDULE_CREATED"))
                .andExpect(jsonPath("$[0].actorUserId").value("dispatcher@shop.com"));
    }

    // ─── AC: GET /v1/shop/audit — 400 for missing filter ─────────────────────

    /**
     * AC (RQ5): Requesting the audit trail without any filter criterion must return
     * HTTP 400
     * to prevent unbounded full-table scans.
     */
    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    void searchAudit_withNoFilterParams_returns400() throws Exception {
        when(shopAuditService.search(any()))
                .thenThrow(new IllegalArgumentException(
                        "At least one filter criterion is required"));

        mockMvc.perform(get("/v1/shop/audit")) // no filter params
                .andExpect(status().isBadRequest());
    }

    // ─── AC: GET /v1/shop/audit/{id} — 200 for existing entry ────────────────

    /**
     * AC: An authorized caller retrieving a known audit entry ID receives HTTP 200
     * and the entry in the response body.
     */
    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    void getAuditById_withExistingId_returns200WithEntry() throws Exception {
        UUID entryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ShopAuditEntryResponse entry = ShopAuditEntryResponse.builder()
                .id(entryId)
                .eventType(ShopAuditEventType.ASSIGNMENT_CREATED)
                .workorderId("WO-123")
                .mechanicId("M-456")
                .actorUserId("advisor@shop.com")
                .build();

        when(shopAuditService.findById(entryId)).thenReturn(Optional.of(entry));

        mockMvc.perform(get("/v1/shop/audit/{id}", entryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entryId.toString()))
                .andExpect(jsonPath("$.eventType").value("ASSIGNMENT_CREATED"))
                .andExpect(jsonPath("$.mechanicId").value("M-456"))
                .andExpect(jsonPath("$.actorUserId").value("advisor@shop.com"));
    }

    // ─── AC: GET /v1/shop/audit/{id} — 404 for missing entry ─────────────────

    /**
     * AC: Requesting an audit entry that does not exist returns HTTP 404.
     */
    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    void getAuditById_withNonExistentId_returns404() throws Exception {
        UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        when(shopAuditService.findById(unknownId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/shop/audit/{id}", unknownId))
                .andExpect(status().isNotFound());
    }

    // ─── AC: Immutability — no DELETE endpoint ────────────────────────────────

    /**
     * AC: No DELETE endpoint exists on {@link ShopAuditController}.
     * Audit entries are immutable once written and must never be deleted via API.
     *
     * <p>
     * This is a structural test; it passes immediately and must remain passing.
     */
    @Test
    void shopAuditController_hasNoDeleteMapping() {
        boolean hasDelete = java.util.Arrays.stream(ShopAuditController.class.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(
                        org.springframework.web.bind.annotation.DeleteMapping.class));

        assertThat(hasDelete)
                .as("ShopAuditController must not define any @DeleteMapping "
                        + "(audit entries are immutable — Story #61 business rule)")
                .isFalse();
    }

    // ─── AC: Immutability — no PATCH or PUT endpoints ────────────────────────

    /**
     * AC: No PATCH or PUT endpoints exist on {@link ShopAuditController}.
     * Audit entries are immutable and must not be modifiable via API.
     *
     * <p>
     * This is a structural test; it passes immediately and must remain passing.
     */
    @Test
    void shopAuditController_hasNoPatchOrPutMapping() {
        var methods = ShopAuditController.class.getDeclaredMethods();

        boolean hasPatch = java.util.Arrays.stream(methods)
                .anyMatch(m -> m.isAnnotationPresent(
                        org.springframework.web.bind.annotation.PatchMapping.class));
        boolean hasPut = java.util.Arrays.stream(methods)
                .anyMatch(m -> m.isAnnotationPresent(
                        org.springframework.web.bind.annotation.PutMapping.class));

        assertThat(hasPatch)
                .as("ShopAuditController must not define any @PatchMapping (immutability)")
                .isFalse();
        assertThat(hasPut)
                .as("ShopAuditController must not define any @PutMapping (immutability)")
                .isFalse();
    }

    // ─── ADR-0017: 401 Unauthenticated ────────────────────────────────────────

    /**
     * ADR-0017: Unauthenticated callers (no credentials at all) receive HTTP 401
     * Unauthorized.
     *
     * <p>
     * No {@code @WithMockUser} or {@code @WithAnonymousUser} annotation is used
     * here:
     * an absent security context causes Spring Security to issue a 401 via the
     * {@code AuthenticationEntryPoint} before any method-security evaluation
     * occurs.
     */
    @Test
    void searchAudit_withNoAuthenticationAtAll_returns401() throws Exception {
        mockMvc.perform(get("/v1/shop/audit")
                .param("workorderId", "WO-123"))
                .andExpect(status().isUnauthorized());
    }

    // ─── ADR-0017: 403 Missing permission ────────────────────────────────────

    /**
     * ADR-0017: An authenticated caller lacking the required audit-view authority
     * receives HTTP 403 Forbidden.
     */
    @Test
    @WithMockUser(authorities = "some.unrelated.authority")
    void searchAudit_withoutAuditViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/shop/audit")
                .param("workorderId", "WO-123"))
                .andExpect(status().isForbidden());
    }

    // ─── Test slice configuration ─────────────────────────────────────────────

    /**
     * Minimal test-slice configuration:
     * <ul>
     * <li>Enables {@code @PreAuthorize} evaluation so 403 tests work.</li>
     * <li>Provides an {@link SecurityExceptionControllerAdvice} that maps
     * Spring Security's {@link AccessDeniedException} to HTTP 403, matching
     * the ADR-0017 contract in this MockMvc dispatcher context.</li>
     * <li>Provides a {@link Clock} bean required by
     * {@link GlobalExceptionHandler}.</li>
     * </ul>
     */
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

    /**
     * Maps Spring Security {@link AccessDeniedException} (and its subclass
     * {@code AuthorizationDeniedException}) to HTTP 403 Forbidden in this
     * MockMvc test-slice context (Spring Boot 4 / Spring Security 6.4 behaviour).
     */
    @ControllerAdvice
    static class SecurityExceptionControllerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
            // status is set by @ResponseStatus
        }

        @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        void handleAuthenticationException() {
            // status is set by @ResponseStatus — covers
            // AuthenticationCredentialsNotFoundException
        }
    }
}
