package com.positivity.workorder.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScope.Reach;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.workorder.internal.dto.WorkorderStatusDetail;
import com.positivity.workorder.internal.dto.WorkorderStatusView;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.LocationHierarchyService;
import com.positivity.workorder.internal.service.WipService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-boundary proof for #1871: {@code GET /v1/workexec/wip} and {@code GET
 * /v1/workexec/wip/{workorderId}} apply the caller's location scope (ADR-0061 §3) on top of
 * {@code workorder:wip:view}; and for #1872: {@code multiLocation=true} narrows a LOCATION-scoped
 * holder of {@code workorder:wip:view_all_locations} to that grant's reach.
 *
 * <p>The {@link LocationScope} is injected through the authentication details map exactly where
 * {@code GatewayAuthoritiesFilter} puts it, with a map-backed {@link LocationAncestorResolver}
 * standing in for the replica. The caller is installed through {@link TestSecurityContextHolder}
 * (the same path {@code @WithMockUser} takes in this module's other slice tests) rather than a
 * request post-processor, which needs the security filter chain inside MockMvc.
 *
 * <p>{@link LocationScopeAutoConfiguration} is imported explicitly: {@code @WebMvcTest} does not
 * load arbitrary {@code @AutoConfiguration} classes, and the point of asserting
 * {@code LOCATION_SCOPE_DENIED} (rather than the module's plain {@code FORBIDDEN}) is to prove the
 * highest-precedence advice wins over this module's {@code AccessDeniedException} handler.
 * {@link SliceTestConfig} is imported explicitly too: a nested {@code @TestConfiguration} is not
 * picked up as a default configuration class for {@code @Nested} test classes.
 */
@WebMvcTest(WipController.class)
@Import({LocationScopeAutoConfiguration.class, WipControllerTest.SliceTestConfig.class})
class WipControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);

    private static final String WIP_URL = "/v1/workexec/wip";
    private static final String WIP_VIEW_ALL_LOCATIONS = "workorder:wip:view_all_locations";

    /** The node the scoped caller is assigned: a region above {@link #SHOP_A}. */
    private static final UUID REGION_NODE = UUID.fromString("019200aa-0000-7000-8000-00000000a000");

    private static final UUID SHOP_A = UUID.fromString("019200aa-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("019200aa-0000-7000-8000-00000000000b");
    private static final UUID UNKNOWN_LOCATION = UUID.fromString("019200aa-0000-7000-8000-0000000000ff");
    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-000000000101");

    /** Replica stand-in: SHOP_A sits under REGION_NODE on the OTHER dimension; SHOP_B does not. */
    private static final Map<UUID, AncestorSets> REPLICA = Map.of(
            SHOP_A, new AncestorSets(Set.of(SHOP_A), Set.of(SHOP_A, REGION_NODE)),
            SHOP_B, new AncestorSets(Set.of(SHOP_B), Set.of(SHOP_B)));

    private static final LocationAncestorResolver RESOLVER =
            locationId -> REPLICA.getOrDefault(locationId, AncestorSets.EMPTY);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WipService wipService;

    @MockitoBean
    private LocationHierarchyService locationHierarchyService;

    @AfterEach
    void clearCaller() {
        TestSecurityContextHolder.clearContext();
    }

    /**
     * Installs the caller for the next request, exactly as {@code @WithMockUser} would, and returns
     * it so a test can also hand it to {@code MockHttpServletRequestBuilder#principal}: the plain
     * {@code Authentication} method argument on {@code listWip} is resolved from
     * {@code request.getUserPrincipal()}, which only the security filter chain populates.
     */
    private static Authentication as(Authentication caller) {
        TestSecurityContextHolder.setAuthentication(caller);
        return caller;
    }

    // ---------------------------------------------------------------------------------------
    // Caller shapes
    // ---------------------------------------------------------------------------------------

    /** A post-rollout token whose {@code workorder:wip:view} is OTHER-scoped to {@link #REGION_NODE}. */
    private static Authentication scopedTechnician() {
        return caller(
                List.of(WorkorderPermissions.WIP_VIEW),
                LocationScope.of(
                        Set.of(),
                        Set.of(WorkorderPermissions.WIP_VIEW),
                        Optional.of(Set.of(REGION_NODE)),
                        true,
                        RESOLVER));
    }

    /** A post-rollout token that carries claims but whose {@code workorder:wip:view} is global. */
    private static Authentication globalViewer() {
        return caller(
                List.of(WorkorderPermissions.WIP_VIEW),
                LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
    }

    /** A pre-rollout token: no {@code loc_*} claims at all, so no scope detail is attached. */
    private static Authentication preRolloutViewer() {
        return caller(List.of(WorkorderPermissions.WIP_VIEW), null);
    }

    /**
     * A caller who holds the widening permission but whose {@code view_all_locations} grant is
     * itself OTHER-scoped to {@link #REGION_NODE} (#1872).
     */
    private static Authentication scopedTechnicianWithScopedViewAll() {
        return caller(
                List.of(WorkorderPermissions.WIP_VIEW, WIP_VIEW_ALL_LOCATIONS),
                LocationScope.of(
                        Set.of(),
                        Set.of(WorkorderPermissions.WIP_VIEW, WIP_VIEW_ALL_LOCATIONS),
                        Optional.of(Set.of(REGION_NODE)),
                        true,
                        RESOLVER));
    }

    /** A caller whose {@code view_all_locations} grant is global even though other grants are scoped. */
    private static Authentication scopedTechnicianWithGlobalViewAll() {
        return caller(
                List.of(WorkorderPermissions.WIP_VIEW, WIP_VIEW_ALL_LOCATIONS),
                LocationScope.of(
                        Set.of(),
                        Set.of(WorkorderPermissions.WIP_VIEW),
                        Optional.of(Set.of(REGION_NODE)),
                        true,
                        RESOLVER));
    }

    /** A widening holder whose token carries the bitset but no {@code loc_scope} nodes: reach is nothing. */
    private static Authentication scopedViewAllWithoutNodes() {
        return caller(
                List.of(WorkorderPermissions.WIP_VIEW, WIP_VIEW_ALL_LOCATIONS),
                LocationScope.of(Set.of(), Set.of(WIP_VIEW_ALL_LOCATIONS), Optional.empty(), true, RESOLVER));
    }

    private static Authentication caller(List<String> authorities, LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(
                "wip-test-user",
                null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        Map<String, Object> details = scope == null
                ? Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "wip-test-user")
                : Map.of(
                        GatewaySecurityConstants.DETAIL_USERNAME,
                        "wip-test-user",
                        GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                        scope);
        token.setDetails(details);
        return token;
    }

    private static WorkorderStatusDetail detailAt(String locationId) {
        return WorkorderStatusDetail.builder()
                .workorderId(WORKORDER_ID)
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .locationId(locationId)
                .customerName("Customer")
                .vehicleInfo("Vehicle")
                .lastUpdatedAt(Instant.parse("2026-09-07T11:00:00Z"))
                .statusHistory(List.of())
                .partsBlocking(List.of())
                .serviceDescription("")
                .build();
    }

    private void stubListFor(UUID locationId) {
        when(wipService.getWipWorkorders(eq(locationId.toString()), eq(false), any(Pageable.class)))
                .thenReturn(new PageImpl<WorkorderStatusView>(List.of()));
    }

    // ---------------------------------------------------------------------------------------
    // GET /v1/workexec/wip — narrow case (multiLocation=false)
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("listWip, multiLocation=false")
    class ListWipNarrow {

        @Test
        @DisplayName("scoped caller with the location in reach answers 200")
        void scopedCallerInReach() throws Exception {
            stubListFor(SHOP_A);

            as(scopedTechnician());
            mockMvc.perform(get(WIP_URL).param("locationId", SHOP_A.toString())).andExpect(status().isOk());

            verify(wipService).getWipWorkorders(eq(SHOP_A.toString()), eq(false), any(Pageable.class));
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void scopedCallerOutOfReach() throws Exception {
            as(scopedTechnician());
            mockMvc.perform(get(WIP_URL).param("locationId", SHOP_B.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.correlationId").exists())
                    .andExpect(header().exists("X-Correlation-Id"));

            verify(wipService, never()).getWipWorkorders(any(), eq(false), any(Pageable.class));
        }

        @Test
        @DisplayName("the 403 envelope does not reflect the caller-supplied locationId")
        void deniedEnvelopeDoesNotEchoLocationId() throws Exception {
            as(scopedTechnician());
            mockMvc.perform(get(WIP_URL).param("locationId", SHOP_B.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SHOP_B.toString()))));
        }

        @Test
        @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour: 200 for any location")
        void preRolloutTokenIsUnchanged() throws Exception {
            stubListFor(SHOP_B);

            as(preRolloutViewer());
            mockMvc.perform(get(WIP_URL).param("locationId", SHOP_B.toString())).andExpect(status().isOk());

            verify(wipService).getWipWorkorders(eq(SHOP_B.toString()), eq(false), any(Pageable.class));
        }

        @Test
        @DisplayName("caller whose workorder:wip:view is global answers 200 for a location outside its nodes")
        void globalGrantIsNotLocationChecked() throws Exception {
            stubListFor(SHOP_B);

            as(globalViewer());
            mockMvc.perform(get(WIP_URL).param("locationId", SHOP_B.toString())).andExpect(status().isOk());
        }

        @Test
        @DisplayName("scoped caller asking for a location the replica does not hold answers 403 (fail closed)")
        void unknownLocationDenies() throws Exception {
            as(scopedTechnician());
            mockMvc.perform(get(WIP_URL).param("locationId", UNKNOWN_LOCATION.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(wipService, never()).getWipWorkorders(any(), eq(false), any(Pageable.class));
        }

        @Test
        @DisplayName("malformed locationId is a 400 for a scoped caller too — validation precedes scope")
        void malformedLocationIdIs400BeforeScope() throws Exception {
            as(scopedTechnician());
            mockMvc.perform(get(WIP_URL).param("locationId", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

            verify(wipService, never()).getWipWorkorders(any(), eq(false), any(Pageable.class));
        }

        @Test
        @DisplayName("caller without workorder:wip:view is refused by @PreAuthorize before any scope check")
        void missingBasePermissionIsForbidden() throws Exception {
            as(caller(List.of("workorder:workorder:view"), null));
            mockMvc.perform(get(WIP_URL).param("locationId", SHOP_A.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    // ---------------------------------------------------------------------------------------
    // GET /v1/workexec/wip — widening case (multiLocation=true)
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("listWip, multiLocation=true")
    class ListWipMulti {

        @Test
        @DisplayName("without workorder:wip:view_all_locations answers 403 FORBIDDEN as before")
        void withoutViewAllIsForbidden() throws Exception {
            Authentication caller = as(scopedTechnician());
            mockMvc.perform(get(WIP_URL)
                            .param("locationId", SHOP_A.toString())
                            .param("multiLocation", "true")
                            .principal(caller))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            verify(wipService, never()).getWipWorkorders(any(), eq(true), any(Pageable.class));
        }

        @Test
        @DisplayName("an unscoped holder of workorder:wip:view_all_locations still sees every location")
        void unscopedViewAllIsNotNarrowed() throws Exception {
            when(wipService.getWipWorkorders(eq(SHOP_B.toString()), eq(true), any(Pageable.class)))
                    .thenReturn(new PageImpl<WorkorderStatusView>(List.of()));

            Authentication caller = as(scopedTechnicianWithGlobalViewAll());
            mockMvc.perform(get(WIP_URL)
                            .param("locationId", SHOP_B.toString())
                            .param("multiLocation", "true")
                            .principal(caller))
                    .andExpect(status().isOk());

            verify(wipService).getWipWorkorders(eq(SHOP_B.toString()), eq(true), any(Pageable.class));
            verify(wipService, never()).getWipWorkordersAtShops(any(), any());
        }

        @Test
        @DisplayName("pre-rollout token with view_all_locations keeps today's behaviour: every location")
        void preRolloutViewAllIsUnchanged() throws Exception {
            when(wipService.getWipWorkorders(eq(SHOP_B.toString()), eq(true), any(Pageable.class)))
                    .thenReturn(new PageImpl<WorkorderStatusView>(List.of()));

            Authentication caller = as(caller(List.of(WorkorderPermissions.WIP_VIEW, WIP_VIEW_ALL_LOCATIONS), null));
            mockMvc.perform(get(WIP_URL)
                            .param("locationId", SHOP_B.toString())
                            .param("multiLocation", "true")
                            .principal(caller))
                    .andExpect(status().isOk());

            verify(wipService).getWipWorkorders(eq(SHOP_B.toString()), eq(true), any(Pageable.class));
        }

        @Test
        @DisplayName("a LOCATION-scoped holder of view_all_locations is narrowed to that grant's reach (#1872)")
        void scopedViewAllIsNarrowedToReach() throws Exception {
            Set<UUID> reachable = Set.of(REGION_NODE, SHOP_A);
            when(locationHierarchyService.reachableLocations(any(Reach.class))).thenReturn(reachable);
            when(wipService.getWipWorkordersAtShops(eq(reachable), any(Pageable.class)))
                    .thenReturn(new PageImpl<WorkorderStatusView>(List.of()));

            Authentication caller = as(scopedTechnicianWithScopedViewAll());
            mockMvc.perform(get(WIP_URL)
                            .param("locationId", SHOP_B.toString())
                            .param("multiLocation", "true")
                            .principal(caller))
                    .andExpect(status().isOk());

            // The reach handed to the replica is the view_all_locations grant's own: OTHER on REGION.
            verify(locationHierarchyService)
                    .reachableLocations(argThat(reach -> reach.nodes().equals(Set.of(REGION_NODE))
                            && reach.dimensions()
                                    .equals(Set.of(
                                            com.positivity.domainevents.location.LocationAncestry.Dimension.OTHER))));
            verify(wipService).getWipWorkordersAtShops(eq(reachable), any(Pageable.class));
            verify(wipService, never()).getWipWorkorders(any(), eq(true), any(Pageable.class));
        }

        @Test
        @DisplayName("a scoped holder whose reach is empty gets an empty page, not every location and not 403")
        void scopedViewAllWithEmptyReachIsEmptyPage() throws Exception {
            when(locationHierarchyService.reachableLocations(any(Reach.class))).thenReturn(Set.of());
            when(wipService.getWipWorkordersAtShops(eq(Set.of()), any(Pageable.class)))
                    .thenReturn(new PageImpl<WorkorderStatusView>(List.of()));

            Authentication caller = as(scopedViewAllWithoutNodes());
            mockMvc.perform(get(WIP_URL)
                            .param("locationId", SHOP_B.toString())
                            .param("multiLocation", "true")
                            .principal(caller))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty());

            verify(wipService).getWipWorkordersAtShops(eq(Set.of()), any(Pageable.class));
            verify(wipService, never()).getWipWorkorders(any(), eq(true), any(Pageable.class));
        }
    }

    // ---------------------------------------------------------------------------------------
    // GET /v1/workexec/wip/{workorderId}
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("getWipDetail")
    class GetWipDetail {

        @Test
        @DisplayName("scoped caller viewing a workorder at a location in reach answers 200")
        void inReach() throws Exception {
            when(wipService.getWipDetail(WORKORDER_ID)).thenReturn(detailAt(SHOP_A.toString()));

            as(scopedTechnician());
            mockMvc.perform(get(WIP_URL + "/{workorderId}", WORKORDER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workorderId").value(WORKORDER_ID.toString()))
                    .andExpect(jsonPath("$.locationId").value(SHOP_A.toString()));
        }

        @Test
        @DisplayName(
                "scoped caller addressing a workorder at a location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            when(wipService.getWipDetail(WORKORDER_ID)).thenReturn(detailAt(SHOP_B.toString()));

            as(scopedTechnician());
            mockMvc.perform(get(WIP_URL + "/{workorderId}", WORKORDER_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.correlationId").exists());
        }

        @Test
        @DisplayName("missing workorder stays 404 for a scoped caller — existence is checked before scope")
        void missingStays404() throws Exception {
            when(wipService.getWipDetail(WORKORDER_ID)).thenThrow(new WorkorderNotFoundException(WORKORDER_ID));

            as(scopedTechnician());
            mockMvc.perform(get(WIP_URL + "/{workorderId}", WORKORDER_ID)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("scoped caller viewing a workorder with no location fails closed with 403")
        void locationlessWorkorderDeniesScopedCaller() throws Exception {
            when(wipService.getWipDetail(WORKORDER_ID)).thenReturn(detailAt(""));

            as(scopedTechnician());
            mockMvc.perform(get(WIP_URL + "/{workorderId}", WORKORDER_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }

        @Test
        @DisplayName("pre-rollout token views any workorder as before")
        void preRolloutTokenIsUnchanged() throws Exception {
            when(wipService.getWipDetail(WORKORDER_ID)).thenReturn(detailAt(SHOP_B.toString()));

            as(preRolloutViewer());
            mockMvc.perform(get(WIP_URL + "/{workorderId}", WORKORDER_ID)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("caller whose workorder:wip:view is global views a workorder outside its nodes")
        void globalGrantIsNotLocationChecked() throws Exception {
            when(wipService.getWipDetail(WORKORDER_ID)).thenReturn(detailAt(SHOP_B.toString()));

            as(globalViewer());
            mockMvc.perform(get(WIP_URL + "/{workorderId}", WORKORDER_ID)).andExpect(status().isOk());
        }
    }

    /** Fixed clock for both advices, plus method security so {@code @PreAuthorize} is enforced. */
    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
