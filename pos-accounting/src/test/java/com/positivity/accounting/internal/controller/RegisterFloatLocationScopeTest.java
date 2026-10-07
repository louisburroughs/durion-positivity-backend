package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.internal.dto.RegisterFloatResponse;
import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.RegisterFloatService;
import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.http.MediaType;
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
 * Controller-boundary proof of the float commands' location-scope gate (#2511, location-scope.yaml: gate): a
 * caller whose {@code accounting:float:manage} is scoped to a region may set and change floats only for that
 * region's registers. The register's own location is held by the service (FLOAT_REGISTER_LOCATION_MISMATCH),
 * so naming an in-scope location cannot reach another location's register.
 */
@WebMvcTest(RegisterFloatController.class)
@Import({LocationScopeAutoConfiguration.class, RegisterFloatLocationScopeTest.SliceTestConfig.class})
@DisplayName("POST /v1/accounting/registers/{registerId}/float[/go-live] — location scope (#2511)")
@SuppressWarnings("java:S6813")
class RegisterFloatLocationScopeTest {

    private static final String GO_LIVE = "/v1/accounting/registers/T-1/float/go-live";
    private static final String CHANGE = "/v1/accounting/registers/T-1/float";
    private static final String RELOCATION = "/v1/accounting/registers/T-1/float/relocation";
    private static final String PERMISSION = AccountingPermissions.FLOAT_MANAGE;

    private static final UUID REGION_NODE = UUID.fromString("019200bb-0000-7000-8000-00000000a000");
    private static final UUID SHOP_A = UUID.fromString("019200bb-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("019200bb-0000-7000-8000-00000000000b");
    private static final UUID SHOP_C = UUID.fromString("019200bb-0000-7000-8000-00000000000c");

    /** SHOP_A and SHOP_C roll up to REGION_NODE on every dimension; SHOP_B on none. */
    private static final Map<UUID, AncestorSets> REPLICA = Map.of(
            SHOP_A, new AncestorSets(Set.of(SHOP_A, REGION_NODE), Set.of(SHOP_A, REGION_NODE)),
            SHOP_B, new AncestorSets(Set.of(SHOP_B), Set.of(SHOP_B)),
            SHOP_C, new AncestorSets(Set.of(SHOP_C, REGION_NODE), Set.of(SHOP_C, REGION_NODE)));

    private static final LocationAncestorResolver RESOLVER =
            locationId -> REPLICA.getOrDefault(locationId, AncestorSets.EMPTY);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegisterFloatService registerFloatService;

    private static Authentication scopedCaller() {
        var scope = LocationScope.of(Set.of(PERMISSION), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER);
        var token = new UsernamePasswordAuthenticationToken(
                "scope-test-user", null, List.of(new SimpleGrantedAuthority(PERMISSION)));
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "scope-test-user",
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
        return token;
    }

    private static String goLiveBody(UUID location) {
        return """
                {"locationId":"%s","amount":200.00,"goLiveDate":"2026-10-01",
                 "justification":"Counted float in drawer 1 at go-live",
                 "requestId":"019a0000-0000-7000-8000-000000000201"}
                """.formatted(location);
    }

    private static String changeBody(UUID location) {
        return """
                {"locationId":"%s","amount":300.00,"bankGlAccountId":"019a0000-0000-7000-8000-00000000b000",
                 "justification":"More change for the weekend",
                 "requestId":"019a0000-0000-7000-8000-000000000202"}
                """.formatted(location);
    }

    private static Authentication callerWithoutFloatManage() {
        var token = new UsernamePasswordAuthenticationToken(
                "scope-test-user", null, List.of(new SimpleGrantedAuthority("accounting:je:view")));
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "scope-test-user"));
        return token;
    }

    private static String relocationBody(UUID from, UUID to) {
        return """
                {"fromLocationId":"%s","toLocationId":"%s","reason":"MOVED",
                 "justification":"Drawer 1 moved to the new shop",
                 "requestId":"019a0000-0000-7000-8000-000000000203"}
                """.formatted(from, to);
    }

    private static RegisterFloatService.Outcome outcome(RegisterFloatChangeKind kind) {
        return new RegisterFloatService.Outcome(
                new RegisterFloatResponse(
                        "T-1",
                        SHOP_A,
                        kind,
                        BigDecimal.ZERO,
                        new BigDecimal("200.00"),
                        LocalDate.of(2026, 10, 1),
                        UUID.fromString("019a0000-0000-7000-8000-00000000e001"),
                        "JE-202610-000001",
                        false),
                false);
    }

    @Test
    @DisplayName("go-live for a location inside the caller's region answers 201")
    void goLiveInReachIsAllowed() throws Exception {
        when(registerFloatService.establishGoLive(eq("T-1"), any()))
                .thenReturn(outcome(RegisterFloatChangeKind.GO_LIVE));

        mockMvc.perform(post(GO_LIVE)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goLiveBody(SHOP_A)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locationId").value(SHOP_A.toString()));
    }

    @Test
    @DisplayName("go-live for a location outside the caller's region answers 403 LOCATION_SCOPE_DENIED, nothing posts")
    void goLiveOutOfReachIsDenied() throws Exception {
        mockMvc.perform(post(GO_LIVE)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goLiveBody(SHOP_B)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

        verify(registerFloatService, never()).establishGoLive(any(), any());
    }

    @Test
    @DisplayName("Change float for a location inside the caller's region answers 201")
    void changeInReachIsAllowed() throws Exception {
        when(registerFloatService.changeFloat(eq("T-1"), any())).thenReturn(outcome(RegisterFloatChangeKind.CHANGE));

        mockMvc.perform(post(CHANGE)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(SHOP_A)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName(
            "Change float for a location outside the caller's region answers 403 LOCATION_SCOPE_DENIED, nothing posts")
    void changeOutOfReachIsDenied() throws Exception {
        mockMvc.perform(post(CHANGE)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(SHOP_B)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

        verify(registerFloatService, never()).changeFloat(any(), any());
    }

    @Test
    @DisplayName("#2571 AC3: a move between two locations inside the caller's region answers 201")
    void relocationInReachIsAllowed() throws Exception {
        when(registerFloatService.relocate(eq("T-1"), any())).thenReturn(outcome(RegisterFloatChangeKind.RELOCATION));

        mockMvc.perform(post(RELOCATION)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(relocationBody(SHOP_A, SHOP_C)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("RELOCATION"));
    }

    @Test
    @DisplayName("#2571 AC3: a move FROM a location outside the caller's region answers 403 LOCATION_SCOPE_DENIED,"
            + " nothing posts")
    void relocationFromOutOfReachIsDenied() throws Exception {
        mockMvc.perform(post(RELOCATION)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(relocationBody(SHOP_B, SHOP_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

        verify(registerFloatService, never()).relocate(any(), any());
    }

    @Test
    @DisplayName("#2571 AC3: a move TO a location outside the caller's region answers 403 LOCATION_SCOPE_DENIED,"
            + " nothing posts")
    void relocationToOutOfReachIsDenied() throws Exception {
        mockMvc.perform(post(RELOCATION)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(relocationBody(SHOP_A, SHOP_B)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

        verify(registerFloatService, never()).relocate(any(), any());
    }

    @Test
    @DisplayName("#2571 AC3: a caller without accounting:float:manage answers 403, nothing posts")
    void relocationWithoutPermissionIsDenied() throws Exception {
        mockMvc.perform(post(RELOCATION)
                        .with(authentication(callerWithoutFloatManage()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(relocationBody(SHOP_A, SHOP_C)))
                .andExpect(status().isForbidden());

        verify(registerFloatService, never()).relocate(any(), any());
    }

    @Test
    @DisplayName("#2571 AC11: a missing reason answers 400 before any scope decision, nothing posts")
    void relocationWithoutReasonIsRejected() throws Exception {
        mockMvc.perform(post(RELOCATION)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromLocationId":"%s","toLocationId":"%s",
                                 "justification":"Drawer 1 moved to the new shop",
                                 "requestId":"019a0000-0000-7000-8000-000000000204"}
                                """.formatted(SHOP_A, SHOP_C)))
                .andExpect(status().isBadRequest());

        verify(registerFloatService, never()).relocate(any(), any());
    }

    /** Method security plus a fixed clock for the denial advice's timestamp; the chain permits every request. */
    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
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
