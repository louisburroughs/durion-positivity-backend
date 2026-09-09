package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.internal.dto.LaborOverheadCostReport;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.LaborOverheadReportService;
import com.positivity.accounting.internal.service.LocationHierarchyService;
import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-boundary proof for #1885 (ADR-0061 §3) on the one pos-accounting operation that names
 * a location: the Labor &amp; Overhead Cost Report is gated on the caller's reach.
 *
 * <p>This module is the clearest FINANCIAL case in the platform — an ACCOUNTANT assigned to a
 * region should see that region's shops and no others — so the scoped caller here is scoped on the
 * {@code FINANCIAL} dimension, and a location reachable only along {@code OTHER} is still denied.
 *
 * <p>Accounting names a location by its GL dimension code rather than by the owner's UUID, so the
 * gate resolves the code through this module's own {@code ext_location} replica first
 * ({@link LocationHierarchyService#locationIdForCode}). A code the replica cannot place reaches the
 * check unparseable and is denied for a scoped caller — the ADR's fail-closed rule — while a global
 * or pre-rollout caller is unaffected.
 */
@WebMvcTest(LaborOverheadReportController.class)
@Import({LocationScopeAutoConfiguration.class, LaborOverheadReportLocationScopeTest.SliceTestConfig.class})
@DisplayName("GET /v1/accounting/reports/location/labor-overhead — location scope (#1885)")
@SuppressWarnings("java:S6813")
class LaborOverheadReportLocationScopeTest {

    private static final String URL = "/v1/accounting/reports/location/labor-overhead";
    private static final String PERMISSION = AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS;

    /** The node the scoped caller is assigned: a financial roll-up above {@link #SHOP_A}. */
    private static final UUID REGION_NODE = UUID.fromString("019200bb-0000-7000-8000-00000000a000");

    private static final UUID SHOP_A = UUID.fromString("019200bb-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("019200bb-0000-7000-8000-00000000000b");

    private static final String CODE_A = "LOC-107";
    private static final String CODE_B = "LOC-204";
    private static final String CODE_UNREPLICATED = "LOC-999";

    /** Replica stand-in: SHOP_A rolls up to REGION_NODE on FINANCIAL; SHOP_B only on OTHER. */
    private static final Map<UUID, AncestorSets> REPLICA = Map.of(
            SHOP_A, new AncestorSets(Set.of(SHOP_A, REGION_NODE), Set.of(SHOP_A)),
            SHOP_B, new AncestorSets(Set.of(SHOP_B), Set.of(SHOP_B, REGION_NODE)));

    private static final LocationAncestorResolver RESOLVER =
            locationId -> REPLICA.getOrDefault(locationId, AncestorSets.EMPTY);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LaborOverheadReportService laborOverheadReportService;

    @MockitoBean
    private LocationHierarchyService locationHierarchyService;

    // ------------------------------------------------------------------ caller shapes

    /** A post-rollout token whose reporting permission is FINANCIAL-scoped to {@link #REGION_NODE}. */
    private static Authentication scopedCaller() {
        return as(LocationScope.of(Set.of(PERMISSION), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
    }

    /** A post-rollout token that carries claims but whose reporting permission is global. */
    private static Authentication globalCaller() {
        return as(LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
    }

    /** A pre-rollout token: no {@code loc_*} claims at all. */
    private static Authentication preRolloutCaller() {
        return as(null);
    }

    private static Authentication as(LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(
                "scope-test-user", null, List.of(new SimpleGrantedAuthority(PERMISSION)));
        token.setDetails(
                scope == null
                        ? Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "scope-test-user")
                        : Map.of(
                                GatewaySecurityConstants.DETAIL_USERNAME,
                                "scope-test-user",
                                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                                scope));
        return token;
    }

    private void replicaHolds(String code, UUID locationId) {
        when(locationHierarchyService.locationIdForCode(code)).thenReturn(Optional.of(locationId));
    }

    private void stubReport(String code) {
        when(laborOverheadReportService.generate(eq(code), eq(2026), any()))
                .thenReturn(LaborOverheadCostReport.builder()
                        .locationId(code)
                        .locationLabel(code)
                        .fiscalYear(2026)
                        .asOfMonth(12)
                        .currency("USD")
                        .localCurrencyPerUsd(new BigDecimal("1.00"))
                        .averageRate(new BigDecimal("1.00"))
                        .lines(List.of())
                        .build());
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("scoped caller reading a location inside the financial roll-up answers 200")
    void inReachIsAllowed() throws Exception {
        replicaHolds(CODE_A, SHOP_A);
        stubReport(CODE_A);

        mockMvc.perform(get(URL).with(authentication(scopedCaller()))
                        .param("locationId", CODE_A)
                        .param("fiscalYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(CODE_A));
    }

    @Test
    @DisplayName("scoped caller reading a location outside the financial roll-up answers 403 LOCATION_SCOPE_DENIED")
    void outOfReachIsDenied() throws Exception {
        replicaHolds(CODE_B, SHOP_B);

        mockMvc.perform(get(URL).with(authentication(scopedCaller()))
                        .param("locationId", CODE_B)
                        .param("fiscalYear", "2026"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                .andExpect(jsonPath("$.status").value(403));

        verify(laborOverheadReportService, never()).generate(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("a location code the replica cannot place is denied for a scoped caller — fail closed")
    void unreplicatedCodeIsDenied() throws Exception {
        when(locationHierarchyService.locationIdForCode(CODE_UNREPLICATED)).thenReturn(Optional.empty());

        mockMvc.perform(get(URL).with(authentication(scopedCaller()))
                        .param("locationId", CODE_UNREPLICATED)
                        .param("fiscalYear", "2026"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

        verify(laborOverheadReportService, never()).generate(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("caller whose grant is global answers 200 for a location outside its nodes")
    void globalGrantIsNotLocationChecked() throws Exception {
        replicaHolds(CODE_B, SHOP_B);
        stubReport(CODE_B);

        mockMvc.perform(get(URL).with(authentication(globalCaller()))
                        .param("locationId", CODE_B)
                        .param("fiscalYear", "2026"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour, replica or no replica")
    void preRolloutUnchanged() throws Exception {
        when(locationHierarchyService.locationIdForCode(CODE_UNREPLICATED)).thenReturn(Optional.empty());
        stubReport(CODE_UNREPLICATED);

        mockMvc.perform(get(URL).with(authentication(preRolloutCaller()))
                        .param("locationId", CODE_UNREPLICATED)
                        .param("fiscalYear", "2026"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an invalid fiscalYear is a 400 for a scoped caller too — validation precedes scope")
    void validationPrecedesScope() throws Exception {
        mockMvc.perform(get(URL).with(authentication(scopedCaller()))
                        .param("locationId", CODE_B)
                        .param("fiscalYear", "12"))
                .andExpect(status().isBadRequest());

        verify(laborOverheadReportService, never()).generate(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    /**
     * Method security plus a fixed clock for the denial advice's timestamp. The chain permits every
     * request because this slice exercises the scope check, not the gateway's authentication:
     * {@code @PreAuthorize} still runs, and the caller is supplied per request exactly as
     * {@code GatewayAuthoritiesFilter} would have built it.
     */
    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @SuppressWarnings("java:S4502") // CSRF disabled: stateless slice, no cookies
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }
}
