package com.positivity.workorder.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScope.Reach;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.workorder.internal.dto.ApprovalConfigurationResponse;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentResponse;
import com.positivity.workorder.internal.dto.DashboardResponse;
import com.positivity.workorder.internal.dto.EstimateResponse;
import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.internal.dto.WorkSessionResponse;
import com.positivity.workorder.internal.exception.EstimateNotFoundException;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.ApprovalConfigurationService;
import com.positivity.workorder.internal.service.DashboardService;
import com.positivity.workorder.internal.service.EstimateService;
import com.positivity.workorder.internal.service.IdempotencyService;
import com.positivity.workorder.internal.service.LaborIntelligenceService;
import com.positivity.workorder.internal.service.LocationHierarchyService;
import com.positivity.workorder.internal.service.WorkSessionService;
import com.positivity.workorder.internal.service.WorkexecTimeTrackingService;
import com.positivity.workorder.internal.service.WorkorderService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Controller-boundary proof for #1872 (ADR-0061 §3) across the location-parameterised
 * pos-workorder endpoints other than WIP (which {@link WipControllerTest} owns):
 *
 * <ul>
 *   <li><b>gate</b> — a required location names the site read or acted on:
 *       {@code getDispatchDashboard}, {@code listEstimatesByShop}, {@code listEstimatesByLocation},
 *       {@code createEstimateFromAppointment}, {@code startWorkexecWorkSession}, and the
 *       resource-addressed siblings {@code getEstimate}, {@code getEstimateSummary} and
 *       {@code generateEstimatePdf} (gated off the loaded estimate's location, after the 404).
 *       {@code getApplicableApprovalConfiguration} gates a supplied location and leaves the
 *       location-less global default alone.</li>
 *   <li><b>narrow</b> — an optional or absent location filter: {@code getJobTimeTotals},
 *       {@code listLaborIntelligence} and the unfiltered {@code listEstimates}. A supplied filter
 *       is gated; without one a scoped caller sees only their reach (an empty reach is an empty
 *       result, not a 403) and an unscoped caller is unrestricted.</li>
 * </ul>
 *
 * <p>The {@link LocationScope} rides in the authentication details map exactly where
 * {@code GatewayAuthoritiesFilter} puts it, with a map-backed {@link LocationAncestorResolver}
 * standing in for the replica; reach expansion is a mocked {@link LocationHierarchyService} so
 * the test pins what each controller hands it and what it does with the answer.
 * {@link LocationScopeAutoConfiguration} is imported so the {@code LOCATION_SCOPE_DENIED} advice
 * wins over the module's plain {@code FORBIDDEN} handler, as in production.
 */
@WebMvcTest({
    DashboardController.class,
    ApprovalConfigurationController.class,
    EstimateController.class,
    EstimateFromAppointmentController.class,
    WorkSessionController.class,
    WorkexecTimeTrackingController.class,
    LaborIntelligenceController.class
})
@Import({LocationScopeAutoConfiguration.class, LocationScopeControllerTest.SliceTestConfig.class})
class LocationScopeControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);

    /** The node the scoped caller is assigned: a region above {@link #SHOP_A}. */
    private static final UUID REGION_NODE = UUID.fromString("019200bb-0000-7000-8000-00000000a000");

    private static final UUID SHOP_A = UUID.fromString("019200bb-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("019200bb-0000-7000-8000-00000000000b");
    private static final UUID ESTIMATE_ID = UUID.fromString("019200bb-0000-7000-8000-000000000101");
    private static final UUID WORKORDER_ID = UUID.fromString("019200bb-0000-7000-8000-000000000102");
    private static final UUID TASK_ID = UUID.fromString("019200bb-0000-7000-8000-000000000103");
    private static final UUID MECHANIC_ID = UUID.fromString("019200bb-0000-7000-8000-000000000104");
    private static final UUID CUSTOMER_ID = UUID.fromString("019200bb-0000-7000-8000-000000000105");
    private static final UUID VEHICLE_ID = UUID.fromString("019200bb-0000-7000-8000-000000000106");
    private static final UUID APPOINTMENT_ID = UUID.fromString("019200bb-0000-7000-8000-000000000107");

    /** Replica stand-in: SHOP_A sits under REGION_NODE on the OTHER dimension; SHOP_B does not. */
    private static final Map<UUID, AncestorSets> REPLICA = Map.of(
            SHOP_A, new AncestorSets(Set.of(SHOP_A), Set.of(SHOP_A, REGION_NODE)),
            SHOP_B, new AncestorSets(Set.of(SHOP_B), Set.of(SHOP_B)));

    private static final LocationAncestorResolver RESOLVER =
            locationId -> REPLICA.getOrDefault(locationId, AncestorSets.EMPTY);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private ApprovalConfigurationService approvalConfigurationService;

    @MockitoBean
    private EstimateService estimateService;

    @MockitoBean
    private WorkorderService workorderService;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @MockitoBean
    private WorkSessionService workSessionService;

    @MockitoBean
    private WorkexecTimeTrackingService workexecTimeTrackingService;

    @MockitoBean
    private LaborIntelligenceService laborIntelligenceService;

    @MockitoBean
    private LocationHierarchyService locationHierarchyService;

    @AfterEach
    void clearCaller() {
        TestSecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------------------
    // Caller shapes
    // ---------------------------------------------------------------------------------------

    /** A post-rollout token whose {@code permission} is OTHER-scoped to {@link #REGION_NODE}. */
    private static void scopedOn(String permission) {
        as(
                List.of(permission),
                LocationScope.of(Set.of(), Set.of(permission), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
    }

    /** A post-rollout token that carries the bitset for {@code permission} but no nodes: reach is nothing. */
    private static void scopedWithoutNodes(String permission) {
        as(List.of(permission), LocationScope.of(Set.of(), Set.of(permission), Optional.empty(), true, RESOLVER));
    }

    /** A post-rollout token that carries claims but whose {@code permission} is global. */
    private static void globalOn(String permission) {
        as(List.of(permission), LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
    }

    /** A pre-rollout token: no {@code loc_*} claims at all, so no scope detail is attached. */
    private static void preRollout(String... authorities) {
        as(List.of(authorities), null);
    }

    private static Authentication as(List<String> authorities, LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(
                "scope-test-user",
                null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        Map<String, Object> details = scope == null
                ? Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "scope-test-user")
                : Map.of(
                        GatewaySecurityConstants.DETAIL_USERNAME,
                        "scope-test-user",
                        GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                        scope);
        token.setDetails(details);
        TestSecurityContextHolder.setAuthentication(token);
        return token;
    }

    private static boolean isOtherReachOn(Reach reach, UUID node) {
        return reach.nodes().equals(Set.of(node)) && reach.dimensions().equals(Set.of(Dimension.OTHER));
    }

    // ---------------------------------------------------------------------------------------
    // DashboardController.getDashboard — gate on workorder:dashboard:view
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("getDispatchDashboard (gate)")
    class Dashboard {

        private static final String URL = "/v1/workexec/dashboard/today";

        @Test
        @DisplayName("scoped caller with the location in reach answers 200")
        void inReach() throws Exception {
            when(dashboardService.getDashboard(eq(SHOP_A.toString()), any(LocalDate.class)))
                    .thenReturn(DashboardResponse.builder().build());

            scopedOn(WorkorderPermissions.DASHBOARD_VIEW);
            mockMvc.perform(get(URL).param("locationId", SHOP_A.toString())).andExpect(status().isOk());

            verify(dashboardService).getDashboard(eq(SHOP_A.toString()), eq(LocalDate.of(2026, 9, 7)));
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            scopedOn(WorkorderPermissions.DASHBOARD_VIEW);
            mockMvc.perform(get(URL).param("locationId", SHOP_B.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.status").value(403));

            verify(dashboardService, never()).getDashboard(any(), any());
        }

        @Test
        @DisplayName("malformed locationId is a 400 for a scoped caller too — validation precedes scope")
        void malformedIs400BeforeScope() throws Exception {
            scopedOn(WorkorderPermissions.DASHBOARD_VIEW);
            mockMvc.perform(get(URL).param("locationId", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

            verify(dashboardService, never()).getDashboard(any(), any());
        }

        @Test
        @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour: 200 for any location")
        void preRolloutUnchanged() throws Exception {
            when(dashboardService.getDashboard(eq(SHOP_B.toString()), any(LocalDate.class)))
                    .thenReturn(DashboardResponse.builder().build());

            preRollout(WorkorderPermissions.DASHBOARD_VIEW);
            mockMvc.perform(get(URL).param("locationId", SHOP_B.toString())).andExpect(status().isOk());
        }

        @Test
        @DisplayName("caller whose grant is global answers 200 for a location outside its nodes")
        void globalGrantIsNotLocationChecked() throws Exception {
            when(dashboardService.getDashboard(eq(SHOP_B.toString()), any(LocalDate.class)))
                    .thenReturn(DashboardResponse.builder().build());

            globalOn(WorkorderPermissions.DASHBOARD_VIEW);
            mockMvc.perform(get(URL).param("locationId", SHOP_B.toString())).andExpect(status().isOk());
        }
    }

    // ---------------------------------------------------------------------------------------
    // ApprovalConfigurationController.getApplicableConfiguration — gate a supplied location
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("getApplicableApprovalConfiguration (gate when supplied)")
    class ApplicableApprovalConfiguration {

        private static final String URL = "/v1/workexec/approvalConfigurations/applicable";

        private ApprovalConfigurationResponse configAt(UUID locationId) {
            return ApprovalConfigurationResponse.builder()
                    .id(UUID.fromString("019200bb-0000-7000-8000-000000000201"))
                    .locationId(locationId)
                    .approvalMethod("SIGNATURE")
                    .build();
        }

        @Test
        @DisplayName("scoped caller resolving a location in reach answers 200")
        void inReach() throws Exception {
            when(approvalConfigurationService.getApplicableConfiguration(SHOP_A, null))
                    .thenReturn(Optional.of(configAt(SHOP_A)));

            scopedOn(WorkorderPermissions.APPROVAL_CONFIG_VIEW);
            mockMvc.perform(get(URL).param("locationId", SHOP_A.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.locationId").value(SHOP_A.toString()));
        }

        @Test
        @DisplayName("scoped caller resolving a location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            scopedOn(WorkorderPermissions.APPROVAL_CONFIG_VIEW);
            mockMvc.perform(get(URL).param("locationId", SHOP_B.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(approvalConfigurationService, never()).getApplicableConfiguration(any(), any());
        }

        @Test
        @DisplayName("scoped caller with no locationId resolves the location-less global default — nothing to gate")
        void absentLocationResolvesGlobalDefault() throws Exception {
            when(approvalConfigurationService.getApplicableConfiguration(null, null))
                    .thenReturn(Optional.of(configAt(null)));

            scopedOn(WorkorderPermissions.APPROVAL_CONFIG_VIEW);
            mockMvc.perform(get(URL)).andExpect(status().isOk());

            verify(approvalConfigurationService).getApplicableConfiguration(isNull(), isNull());
        }

        @Test
        @DisplayName("pre-rollout token resolves any location as before")
        void preRolloutUnchanged() throws Exception {
            when(approvalConfigurationService.getApplicableConfiguration(SHOP_B, null))
                    .thenReturn(Optional.of(configAt(SHOP_B)));

            preRollout(WorkorderPermissions.APPROVAL_CONFIG_VIEW);
            mockMvc.perform(get(URL).param("locationId", SHOP_B.toString())).andExpect(status().isOk());
        }
    }

    // ---------------------------------------------------------------------------------------
    // EstimateController — gate the two location lists and the by-id sibling
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("listEstimatesByShop / listEstimatesByLocation / getEstimate (gate)")
    class Estimates {

        private static final String BASE = "/v1/workorders/estimates";

        private EstimateResponse estimateAt(UUID locationId) {
            return EstimateResponse.builder()
                    .id(ESTIMATE_ID)
                    .estimateNumber("EST-0001")
                    .customerId(CUSTOMER_ID)
                    .vehicleId(VEHICLE_ID)
                    .locationId(locationId)
                    .status("DRAFT")
                    .build();
        }

        @Test
        @DisplayName("listEstimatesByShop: in reach answers 200, out of reach 403 LOCATION_SCOPE_DENIED")
        void byShop() throws Exception {
            when(estimateService.getEstimatesByLocation(SHOP_A)).thenReturn(List.of(estimateAt(SHOP_A)));

            scopedOn(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/shop/{locationId}", SHOP_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].locationId").value(SHOP_A.toString()));
            mockMvc.perform(get(BASE + "/shop/{locationId}", SHOP_B))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(estimateService, never()).getEstimatesByLocation(SHOP_B);
        }

        @Test
        @DisplayName("listEstimatesByLocation: in reach answers 200, out of reach 403 LOCATION_SCOPE_DENIED")
        void byLocation() throws Exception {
            when(estimateService.getEstimatesByLocation(SHOP_A)).thenReturn(List.of(estimateAt(SHOP_A)));

            scopedOn(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/location/{locationId}", SHOP_A)).andExpect(status().isOk());
            mockMvc.perform(get(BASE + "/location/{locationId}", SHOP_B))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(estimateService, never()).getEstimatesByLocation(SHOP_B);
        }

        @Test
        @DisplayName("lists: pre-rollout token reads any shop as before")
        void listsPreRolloutUnchanged() throws Exception {
            when(estimateService.getEstimatesByLocation(SHOP_B)).thenReturn(List.of());

            preRollout(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/shop/{locationId}", SHOP_B)).andExpect(status().isOk());
            mockMvc.perform(get(BASE + "/location/{locationId}", SHOP_B)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("getEstimate: scoped caller viewing an estimate at a location in reach answers 200")
        void byIdInReach() throws Exception {
            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.of(estimateAt(SHOP_A)));

            scopedOn(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/{estimateId}", ESTIMATE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ESTIMATE_ID.toString()));
        }

        @Test
        @DisplayName("getEstimate: an estimate at a location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void byIdOutOfReach() throws Exception {
            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.of(estimateAt(SHOP_B)));

            scopedOn(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/{estimateId}", ESTIMATE_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }

        @Test
        @DisplayName("getEstimate: missing estimate stays 404 for a scoped caller — existence precedes scope")
        void byIdMissingStays404() throws Exception {
            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.empty());

            scopedOn(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/{estimateId}", ESTIMATE_ID)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("getEstimate: an estimate with no location fails closed for a scoped caller")
        void byIdLocationlessDenies() throws Exception {
            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.of(estimateAt(null)));

            scopedOn(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/{estimateId}", ESTIMATE_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }

        @Test
        @DisplayName("getEstimate: pre-rollout and global-grant callers view any estimate as before")
        void byIdUnscopedUnchanged() throws Exception {
            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.of(estimateAt(SHOP_B)));

            preRollout(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/{estimateId}", ESTIMATE_ID)).andExpect(status().isOk());

            globalOn(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/{estimateId}", ESTIMATE_ID)).andExpect(status().isOk());
        }

        // ---- listEstimates (narrow) ----

        @Test
        @DisplayName("listEstimates: a scoped caller sees only estimates at locations within reach")
        void listAllNarrowsToReach() throws Exception {
            Set<UUID> reachable = Set.of(REGION_NODE, SHOP_A);
            when(locationHierarchyService.reachableLocations(any(Reach.class))).thenReturn(reachable);
            when(estimateService.getEstimatesAtLocations(reachable)).thenReturn(List.of(estimateAt(SHOP_A)));

            scopedOn(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].locationId").value(SHOP_A.toString()));

            verify(locationHierarchyService).reachableLocations(argThat(reach -> isOtherReachOn(reach, REGION_NODE)));
            verify(estimateService, never()).getAllEstimates();
        }

        @Test
        @DisplayName("listEstimates: a scoped caller whose reach is empty gets an empty list, not everything")
        void listAllEmptyReachIsEmpty() throws Exception {
            when(locationHierarchyService.reachableLocations(any(Reach.class))).thenReturn(Set.of());

            scopedWithoutNodes(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());

            verify(estimateService, never()).getAllEstimates();
            verify(estimateService, never()).getEstimatesAtLocations(any());
        }

        @Test
        @DisplayName("listEstimates: pre-rollout and global-grant callers get the unfiltered list as before")
        void listAllUnscopedUnchanged() throws Exception {
            when(estimateService.getAllEstimates()).thenReturn(List.of(estimateAt(SHOP_B)));

            preRollout(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE)).andExpect(status().isOk());
            globalOn(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE)).andExpect(status().isOk());

            verify(estimateService, org.mockito.Mockito.times(2)).getAllEstimates();
            verify(locationHierarchyService, never()).reachableLocations(any());
        }

        // ---- getEstimateSummary (gated sibling) ----

        @Test
        @DisplayName("getEstimateSummary: in reach answers 200, out of reach 403, missing stays 404")
        void summarySibling() throws Exception {
            scopedOn(WorkorderPermissions.ESTIMATE_VIEW);

            when(estimateService.getEstimateSummary(ESTIMATE_ID))
                    .thenReturn(
                            EstimateSummaryResponse.builder().locationId(SHOP_A).build());
            mockMvc.perform(get(BASE + "/{estimateId}/summary", ESTIMATE_ID)).andExpect(status().isOk());

            when(estimateService.getEstimateSummary(ESTIMATE_ID))
                    .thenReturn(
                            EstimateSummaryResponse.builder().locationId(SHOP_B).build());
            mockMvc.perform(get(BASE + "/{estimateId}/summary", ESTIMATE_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            when(estimateService.getEstimateSummary(ESTIMATE_ID)).thenThrow(new EstimateNotFoundException(ESTIMATE_ID));
            mockMvc.perform(get(BASE + "/{estimateId}/summary", ESTIMATE_ID)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("getEstimateSummary: pre-rollout token views any summary as before")
        void summaryPreRolloutUnchanged() throws Exception {
            when(estimateService.getEstimateSummary(ESTIMATE_ID))
                    .thenReturn(
                            EstimateSummaryResponse.builder().locationId(SHOP_B).build());

            preRollout(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/{estimateId}/summary", ESTIMATE_ID)).andExpect(status().isOk());
        }

        // ---- generateEstimatePdf (gated sibling) ----

        @Test
        @DisplayName("generateEstimatePdf: in reach renders, out of reach is 403 and never renders")
        void pdfSibling() throws Exception {
            when(estimateService.generateEstimatePdf(ESTIMATE_ID)).thenReturn(new byte[] {1, 2, 3});
            scopedOn(WorkorderPermissions.ESTIMATE_VIEW);

            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.of(estimateAt(SHOP_A)));
            mockMvc.perform(get(BASE + "/{estimateId}/pdf", ESTIMATE_ID)).andExpect(status().isOk());
            verify(estimateService).generateEstimatePdf(ESTIMATE_ID);

            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.of(estimateAt(SHOP_B)));
            // The denial is raised before the renderer's try/catch, so it is a 403 — never a 502.
            mockMvc.perform(get(BASE + "/{estimateId}/pdf", ESTIMATE_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
            verify(estimateService, org.mockito.Mockito.times(1)).generateEstimatePdf(ESTIMATE_ID);
        }

        @Test
        @DisplayName("generateEstimatePdf: missing estimate stays 404 for a scoped caller")
        void pdfMissingStays404() throws Exception {
            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.empty());

            scopedOn(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/{estimateId}/pdf", ESTIMATE_ID)).andExpect(status().isNotFound());

            verify(estimateService, never()).generateEstimatePdf(any());
        }

        @Test
        @DisplayName("generateEstimatePdf: pre-rollout token renders any estimate as before")
        void pdfPreRolloutUnchanged() throws Exception {
            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.of(estimateAt(SHOP_B)));
            when(estimateService.generateEstimatePdf(ESTIMATE_ID)).thenReturn(new byte[] {1});

            preRollout(WorkorderPermissions.ESTIMATE_VIEW);
            mockMvc.perform(get(BASE + "/{estimateId}/pdf", ESTIMATE_ID)).andExpect(status().isOk());
        }
    }

    // ---------------------------------------------------------------------------------------
    // EstimateFromAppointmentController.createEstimateFromAppointment — gate on estimate:create
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("createEstimateFromAppointment (gate)")
    class EstimateFromAppointment {

        private static final String URL = "/v1/workorders/estimates/from-appointment";

        private String body(UUID locationId) {
            return """
                    {"idempotencyKey":"019200bb-0000-7000-8000-000000000301",
                     "appointmentId":"%s","customerId":"%s","vehicleId":"%s","locationId":"%s"}
                    """.formatted(APPOINTMENT_ID, CUSTOMER_ID, VEHICLE_ID, locationId);
        }

        @Test
        @DisplayName("scoped caller opening an estimate at a location in reach answers 201")
        void inReach() throws Exception {
            when(estimateService.createEstimateFromAppointment(any()))
                    .thenReturn(CreateEstimateFromAppointmentResponse.builder()
                            .estimateId(ESTIMATE_ID)
                            .status("DRAFT")
                            .created(true)
                            .build());

            scopedOn(WorkorderPermissions.ESTIMATE_CREATE);
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(SHOP_A)))
                    .andExpect(status().isCreated());

            verify(estimateService).createEstimateFromAppointment(argThat(r -> SHOP_A.equals(r.getLocationId())));
        }

        @Test
        @DisplayName("scoped caller opening an estimate at a location out of reach answers 403 and writes nothing")
        void outOfReach() throws Exception {
            scopedOn(WorkorderPermissions.ESTIMATE_CREATE);
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(SHOP_B)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(estimateService, never()).createEstimateFromAppointment(any());
        }

        @Test
        @DisplayName("pre-rollout token opens an estimate at any location as before")
        void preRolloutUnchanged() throws Exception {
            when(estimateService.createEstimateFromAppointment(any()))
                    .thenReturn(CreateEstimateFromAppointmentResponse.builder()
                            .estimateId(ESTIMATE_ID)
                            .status("DRAFT")
                            .created(false)
                            .build());

            preRollout(WorkorderPermissions.ESTIMATE_CREATE);
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(SHOP_B)))
                    .andExpect(status().isOk());
        }
    }

    // ---------------------------------------------------------------------------------------
    // WorkSessionController.startWorkexecWorkSession — gate on timekeeping:work_session:create
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("startWorkexecWorkSession (gate)")
    class StartWorkSession {

        private static final String URL = "/v1/workorders/workSessions/start";

        private String body(UUID locationId) {
            return """
                    {"mechanicId":"%s","workOrderId":"%s","workOrderTaskId":"%s","locationId":"%s"}
                    """.formatted(MECHANIC_ID, WORKORDER_ID, TASK_ID, locationId);
        }

        @Test
        @DisplayName("scoped caller clocking onto a task at a location in reach answers 201")
        void inReach() throws Exception {
            when(workSessionService.startSession(any()))
                    .thenReturn(WorkSessionResponse.builder()
                            .workSessionId(UUID.fromString("019200bb-0000-7000-8000-000000000401"))
                            .locationId(SHOP_A)
                            .build());

            scopedOn(WorkorderPermissions.TIMEKEEPING_WORK_SESSION_CREATE);
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(SHOP_A)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.locationId").value(SHOP_A.toString()));
        }

        @Test
        @DisplayName("scoped caller clocking onto a task at a location out of reach answers 403 and writes nothing")
        void outOfReach() throws Exception {
            scopedOn(WorkorderPermissions.TIMEKEEPING_WORK_SESSION_CREATE);
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(SHOP_B)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(workSessionService, never()).startSession(any());
        }

        @Test
        @DisplayName("pre-rollout token clocks onto a task at any location as before")
        void preRolloutUnchanged() throws Exception {
            when(workSessionService.startSession(any()))
                    .thenReturn(WorkSessionResponse.builder().locationId(SHOP_B).build());

            preRollout(WorkorderPermissions.TIMEKEEPING_WORK_SESSION_CREATE);
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(SHOP_B)))
                    .andExpect(status().isCreated());
        }
    }

    // ---------------------------------------------------------------------------------------
    // WorkexecTimeTrackingController.getJobTimeTotals — narrow on workorder:labor:view
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("getJobTimeTotals (narrow)")
    class JobTimeTotals {

        private static final String URL = "/v1/workexec/job-time-totals";

        private MockHttpServletRequestBuilder request() {
            return get(URL).param("startDate", "2026-03-01")
                    .param("endDate", "2026-03-02")
                    .param("timezone", "UTC");
        }

        @Test
        @DisplayName("a supplied location in reach is gated and passed through as the only location")
        void filterInReach() throws Exception {
            when(workexecTimeTrackingService.getJobTimeTotals(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            scopedOn(WorkorderPermissions.LABOR_VIEW);
            mockMvc.perform(request().param("locationId", SHOP_A.toString())).andExpect(status().isOk());

            verify(workexecTimeTrackingService)
                    .getJobTimeTotals(any(), any(), any(), eq(Set.of(SHOP_A)), eq(List.of()));
            verify(locationHierarchyService, never()).reachableLocations(any());
        }

        @Test
        @DisplayName("a supplied location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void filterOutOfReach() throws Exception {
            scopedOn(WorkorderPermissions.LABOR_VIEW);
            mockMvc.perform(request().param("locationId", SHOP_B.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(workexecTimeTrackingService, never()).getJobTimeTotals(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("no filter: a scoped caller sees only their reach")
        void noFilterNarrowsToReach() throws Exception {
            Set<UUID> reachable = Set.of(REGION_NODE, SHOP_A);
            when(locationHierarchyService.reachableLocations(any(Reach.class))).thenReturn(reachable);
            when(workexecTimeTrackingService.getJobTimeTotals(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            scopedOn(WorkorderPermissions.LABOR_VIEW);
            mockMvc.perform(request()).andExpect(status().isOk());

            verify(locationHierarchyService).reachableLocations(argThat(reach -> isOtherReachOn(reach, REGION_NODE)));
            verify(workexecTimeTrackingService).getJobTimeTotals(any(), any(), any(), eq(reachable), eq(List.of()));
        }

        @Test
        @DisplayName("no filter: a scoped caller whose reach is empty gets an empty report, not 403 and not everything")
        void noFilterEmptyReachIsEmpty() throws Exception {
            when(locationHierarchyService.reachableLocations(any(Reach.class))).thenReturn(Set.of());

            scopedWithoutNodes(WorkorderPermissions.LABOR_VIEW);
            mockMvc.perform(request())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());

            verify(workexecTimeTrackingService, never()).getJobTimeTotals(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("no filter: pre-rollout and global-grant callers are unrestricted")
        void noFilterUnscopedUnchanged() throws Exception {
            when(workexecTimeTrackingService.getJobTimeTotals(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            preRollout(WorkorderPermissions.LABOR_VIEW);
            mockMvc.perform(request()).andExpect(status().isOk());
            globalOn(WorkorderPermissions.LABOR_VIEW);
            mockMvc.perform(request()).andExpect(status().isOk());

            verify(workexecTimeTrackingService, org.mockito.Mockito.times(2))
                    .getJobTimeTotals(any(), any(), any(), isNull(), eq(List.of()));
            verify(locationHierarchyService, never()).reachableLocations(any());
        }
    }

    // ---------------------------------------------------------------------------------------
    // LaborIntelligenceController.listLaborIntelligence — narrow on workorder:labor_intelligence:view
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("listLaborIntelligence (narrow)")
    class LaborIntelligence {

        private static final String URL = "/v1/workorders/labor-intelligence/operations";

        @Test
        @DisplayName("a supplied shop in reach is gated and passed through as the only shop")
        void filterInReach() throws Exception {
            when(laborIntelligenceService.operations(any(), any(), any())).thenReturn(List.of());

            scopedOn(WorkorderPermissions.LABOR_INTELLIGENCE_VIEW);
            mockMvc.perform(get(URL).param("locationId", SHOP_A.toString())).andExpect(status().isOk());

            verify(laborIntelligenceService).operations(isNull(), eq(Set.of(SHOP_A)), isNull());
        }

        @Test
        @DisplayName("a supplied shop out of reach answers 403 LOCATION_SCOPE_DENIED")
        void filterOutOfReach() throws Exception {
            scopedOn(WorkorderPermissions.LABOR_INTELLIGENCE_VIEW);
            mockMvc.perform(get(URL).param("locationId", SHOP_B.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(laborIntelligenceService, never()).operations(any(), any(), any());
        }

        @Test
        @DisplayName("no filter: a scoped caller sees only their reach")
        void noFilterNarrowsToReach() throws Exception {
            Set<UUID> reachable = Set.of(REGION_NODE, SHOP_A);
            when(locationHierarchyService.reachableLocations(any(Reach.class))).thenReturn(reachable);
            when(laborIntelligenceService.operations(any(), any(), any())).thenReturn(List.of());

            scopedOn(WorkorderPermissions.LABOR_INTELLIGENCE_VIEW);
            mockMvc.perform(get(URL).param("operationCode", "TIRE-ROTATION")).andExpect(status().isOk());

            verify(locationHierarchyService).reachableLocations(argThat(reach -> isOtherReachOn(reach, REGION_NODE)));
            verify(laborIntelligenceService).operations(eq("TIRE-ROTATION"), eq(reachable), isNull());
        }

        @Test
        @DisplayName("no filter: a scoped caller whose reach is empty gets an empty report")
        void noFilterEmptyReachIsEmpty() throws Exception {
            when(locationHierarchyService.reachableLocations(any(Reach.class))).thenReturn(Set.of());

            scopedWithoutNodes(WorkorderPermissions.LABOR_INTELLIGENCE_VIEW);
            mockMvc.perform(get(URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());

            verify(laborIntelligenceService, never()).operations(any(), any(), any());
        }

        @Test
        @DisplayName("no filter: pre-rollout callers and ROLE_ADMIN without the permission are unrestricted")
        void noFilterUnscopedUnchanged() throws Exception {
            when(laborIntelligenceService.operations(any(), any(), any())).thenReturn(List.of());

            preRollout(WorkorderPermissions.LABOR_INTELLIGENCE_VIEW);
            mockMvc.perform(get(URL)).andExpect(status().isOk());
            // ROLE_ADMIN passes @PreAuthorize by role; the permission is in no bitset, so no reach.
            as(
                    List.of("ROLE_ADMIN"),
                    LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
            mockMvc.perform(get(URL)).andExpect(status().isOk());

            verify(laborIntelligenceService, org.mockito.Mockito.times(2)).operations(isNull(), isNull(), isNull());
            verify(locationHierarchyService, never()).reachableLocations(any());
        }
    }

    /** Fixed clock for the dashboard's default date and the error advice, plus method security. */
    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
