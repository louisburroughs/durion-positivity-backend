package com.positivity.order.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.order.internal.dto.RegisterSessionSummary;
import com.positivity.order.internal.exception.RegisterSessionNotFoundException;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.RegisterSessionService;
import com.positivity.order.internal.service.model.OpenSessionCommand;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-boundary proof for #1872 (ADR-0061 §3) on the register-session routes.
 *
 * <p>{@code POST /v1/orders/sessions} is gated in {@code RegisterSessionServiceImpl} on the
 * resolved location (so the terminal-default case cannot bypass it); here the service is a mock and
 * the slice proves its denial reaches the client as {@code 403 LOCATION_SCOPE_DENIED} rather than
 * this module's plain {@code FORBIDDEN}. {@code GET /v1/orders/sessions/{sessionId}} is gated in
 * the controller on the stored session's location, after the 404.
 *
 * <p>Same shape as pos-workorder's {@code WipControllerTest}: the caller is installed through
 * {@link TestSecurityContextHolder} with the {@link LocationScope} in the authentication details
 * exactly where {@code GatewayAuthoritiesFilter} puts it, and only method security is enabled —
 * a {@code @WebMvcTest} slice runs MockMvc without Spring Security's filter chain, and the gateway
 * chain cannot express a scoped caller through the legacy {@code X-Authorities} header anyway.
 * {@link LocationScopeAutoConfiguration} is imported explicitly because a slice does not load
 * library auto-configuration on its own.
 */
@WebMvcTest(RegisterSessionController.class)
@Import({LocationScopeAutoConfiguration.class, RegisterSessionControllerLocationScopeTest.SliceTestConfig.class})
@DisplayName("RegisterSessionController location scope (ADR-0061, #1872)")
class RegisterSessionControllerLocationScopeTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);

    private static final String SESSIONS_URL = "/v1/orders/sessions";

    /** The node the scoped caller is assigned: a region above {@link #SHOP_A}. */
    private static final UUID REGION_NODE = UUID.fromString("019200aa-0000-7000-8000-00000000a000");

    private static final UUID SHOP_A = UUID.fromString("019200aa-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("019200aa-0000-7000-8000-00000000000b");
    private static final UUID SESSION_ID = UUID.fromString("019200aa-0000-7000-8000-000000000101");

    /** Replica stand-in: SHOP_A sits under REGION_NODE on the OTHER dimension; SHOP_B does not. */
    private static final Map<UUID, AncestorSets> REPLICA = Map.of(
            SHOP_A, new AncestorSets(Set.of(SHOP_A), Set.of(SHOP_A, REGION_NODE)),
            SHOP_B, new AncestorSets(Set.of(SHOP_B), Set.of(SHOP_B)));

    private static final LocationAncestorResolver RESOLVER =
            locationId -> REPLICA.getOrDefault(locationId, AncestorSets.EMPTY);

    private static final List<String> SESSION_AUTHORITIES =
            List.of(OrderPermissions.ORDER_SESSION_OPEN, OrderPermissions.ORDER_SESSION_VIEW);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegisterSessionService registerSessionService;

    @AfterEach
    void clearCaller() {
        TestSecurityContextHolder.clearContext();
    }

    /** A post-rollout token whose session permissions are OTHER-scoped to REGION_NODE. */
    private static Authentication scopedCashier() {
        return caller(LocationScope.of(
                Set.of(), Set.copyOf(SESSION_AUTHORITIES), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
    }

    /** A post-rollout token that carries claims but whose session permissions are global. */
    private static Authentication globalCashier() {
        return caller(LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
    }

    /** A pre-rollout token: no {@code loc_*} claims at all, so no scope detail is attached. */
    private static Authentication preRolloutCashier() {
        return caller(null);
    }

    private static void as(Authentication caller) {
        TestSecurityContextHolder.setAuthentication(caller);
    }

    private static Authentication caller(LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(
                "session-test-user",
                null,
                SESSION_AUTHORITIES.stream().map(SimpleGrantedAuthority::new).toList());
        Map<String, Object> details = scope == null
                ? Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "session-test-user")
                : Map.of(
                        GatewaySecurityConstants.DETAIL_USERNAME,
                        "session-test-user",
                        GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                        scope);
        token.setDetails(details);
        return token;
    }

    private static RegisterSessionSummary sessionAt(UUID locationId) {
        return new RegisterSessionSummary(
                SESSION_ID,
                "terminal-1",
                locationId,
                "clerk-1",
                "OPEN",
                new BigDecimal("100.00"),
                null,
                null,
                null,
                false,
                null,
                Instant.parse("2026-09-07T08:00:00Z"),
                null,
                null);
    }

    private static String openBody(UUID locationId) {
        String location = locationId == null ? "" : ",\"locationId\":\"" + locationId + "\"";
        return "{\"terminalId\":\"terminal-1\",\"openedByClerkId\":\"clerk-1\"" + location + "}";
    }

    // ---------------------------------------------------------------------------------------
    // POST /v1/orders/sessions — gate lives in the service; the slice proves the envelope
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("openSession")
    class OpenSession {

        @Test
        @DisplayName("a session the service lets through answers 201")
        void inReach() throws Exception {
            when(registerSessionService.openSession(any(OpenSessionCommand.class)))
                    .thenReturn(sessionAt(SHOP_A));

            as(scopedCashier());
            mockMvc.perform(post(SESSIONS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(openBody(SHOP_A)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.locationId").value(SHOP_A.toString()));
        }

        @Test
        @DisplayName("a service-side scope denial answers 403 LOCATION_SCOPE_DENIED, not the module's FORBIDDEN")
        void outOfReach() throws Exception {
            when(registerSessionService.openSession(any(OpenSessionCommand.class)))
                    .thenThrow(
                            new LocationScopeDeniedException(OrderPermissions.ORDER_SESSION_OPEN, SHOP_B.toString()));

            as(scopedCashier());
            mockMvc.perform(post(SESSIONS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(openBody(SHOP_B)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.correlationId").exists())
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SHOP_B.toString()))));
        }

        @Test
        @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour: 201")
        void preRolloutTokenIsUnchanged() throws Exception {
            when(registerSessionService.openSession(any(OpenSessionCommand.class)))
                    .thenReturn(sessionAt(SHOP_B));

            as(preRolloutCashier());
            mockMvc.perform(post(SESSIONS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(openBody(SHOP_B)))
                    .andExpect(status().isCreated());
        }
    }

    // ---------------------------------------------------------------------------------------
    // GET /v1/orders/sessions/{sessionId} — resource-addressed sibling, gated in the controller
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("getSession")
    class GetSession {

        @Test
        @DisplayName("scoped caller viewing a session at a location in reach answers 200")
        void inReach() throws Exception {
            when(registerSessionService.getSession(SESSION_ID)).thenReturn(sessionAt(SHOP_A));

            as(scopedCashier());
            mockMvc.perform(get(SESSIONS_URL + "/{sessionId}", SESSION_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                    .andExpect(jsonPath("$.locationId").value(SHOP_A.toString()));
        }

        @Test
        @DisplayName("scoped caller addressing a session at a location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            when(registerSessionService.getSession(SESSION_ID)).thenReturn(sessionAt(SHOP_B));

            as(scopedCashier());
            mockMvc.perform(get(SESSIONS_URL + "/{sessionId}", SESSION_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.correlationId").exists());
        }

        @Test
        @DisplayName("missing session stays 404 for a scoped caller — existence is checked before scope")
        void missingStays404() throws Exception {
            when(registerSessionService.getSession(SESSION_ID))
                    .thenThrow(new RegisterSessionNotFoundException(SESSION_ID));

            as(scopedCashier());
            mockMvc.perform(get(SESSIONS_URL + "/{sessionId}", SESSION_ID)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("scoped caller viewing a session with no location fails closed with 403")
        void locationlessSessionDeniesScopedCaller() throws Exception {
            when(registerSessionService.getSession(SESSION_ID)).thenReturn(sessionAt(null));

            as(scopedCashier());
            mockMvc.perform(get(SESSIONS_URL + "/{sessionId}", SESSION_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }

        @Test
        @DisplayName("pre-rollout token views any session as before")
        void preRolloutTokenIsUnchanged() throws Exception {
            when(registerSessionService.getSession(SESSION_ID)).thenReturn(sessionAt(SHOP_B));

            as(preRolloutCashier());
            mockMvc.perform(get(SESSIONS_URL + "/{sessionId}", SESSION_ID)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("caller whose order:session:view is global views a session outside its nodes")
        void globalGrantIsNotLocationChecked() throws Exception {
            when(registerSessionService.getSession(SESSION_ID)).thenReturn(sessionAt(SHOP_B));

            as(globalCashier());
            mockMvc.perform(get(SESSIONS_URL + "/{sessionId}", SESSION_ID)).andExpect(status().isOk());

            verify(registerSessionService).getSession(SESSION_ID);
            verify(registerSessionService, never()).openSession(any());
        }
    }

    /** Fixed clock for the advices, plus method security so {@code @PreAuthorize} is enforced. */
    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
