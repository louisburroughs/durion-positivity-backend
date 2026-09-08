package com.positivity.shopmanager.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.dto.ScheduleViewRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewResponse;
import com.positivity.shopmanager.internal.dto.ShopDashboardResponse;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.AppointmentsService;
import com.positivity.shopmanager.internal.service.MechanicRosterQueryService;
import com.positivity.shopmanager.internal.service.ShopDashboardService;
import com.positivity.shopmanager.internal.service.TechnicianPersonService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Controller-boundary proof for #1872 (ADR-0061 §3): every location-parameterised operation in
 * this module gates the caller's location scope on top of its {@code @PreAuthorize} permission —
 * {@code createAppointment}, {@code viewSchedule}, {@code getShopDashboard},
 * {@code listLocationTechnicians}, {@code getTechnicianPerson} — and the resource-addressed
 * appointment siblings (by-id read, reschedule, cancel) are gated on the stored appointment's
 * location after the 404 so the create gate cannot be bypassed.
 *
 * <p>The {@link LocationScope} is injected through the authentication details map exactly where
 * {@code GatewayAuthoritiesFilter} puts it, with a map-backed {@link LocationAncestorResolver}
 * standing in for the {@code ext_location} replica. {@link LocationScopeAutoConfiguration} is
 * imported explicitly ({@code @WebMvcTest} does not load arbitrary auto-configurations) so the
 * assertion on {@code LOCATION_SCOPE_DENIED} proves the highest-precedence advice wins over this
 * module's own handlers.
 */
@WebMvcTest({
    AppointmentsController.class,
    ScheduleController.class,
    ShopDashboardController.class,
    TechnicianController.class
})
@Import({
    LocationScopeAutoConfiguration.class,
    GlobalExceptionHandler.class,
    LocationScopeControllerTest.SliceTestConfig.class
})
@DisplayName("pos-shop-manager location-scope gates (ADR-0061, #1872)")
class LocationScopeControllerTest {

    /** The node the scoped caller is assigned: a region above {@link #SHOP_A}. */
    private static final UUID REGION_NODE = UUID.fromString("019200aa-0000-7000-8000-00000000a000");

    private static final UUID SHOP_A = UUID.fromString("019200aa-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("019200aa-0000-7000-8000-00000000000b");
    private static final UUID UNKNOWN_LOCATION = UUID.fromString("019200aa-0000-7000-8000-0000000000ff");
    private static final UUID APPOINTMENT_ID = UUID.fromString("019200aa-0000-7000-8000-000000000101");
    private static final UUID PERSON_ID = UUID.fromString("019200aa-0000-7000-8000-000000000201");

    /** Replica stand-in: SHOP_A sits under REGION_NODE on the OTHER dimension; SHOP_B does not. */
    private static final Map<UUID, AncestorSets> REPLICA = Map.of(
            SHOP_A, new AncestorSets(Set.of(SHOP_A), Set.of(SHOP_A, REGION_NODE)),
            SHOP_B, new AncestorSets(Set.of(SHOP_B), Set.of(SHOP_B)));

    private static final LocationAncestorResolver RESOLVER =
            locationId -> REPLICA.getOrDefault(locationId, AncestorSets.EMPTY);

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    /**
     * Stands in for the module's plain {@code AccessDeniedException} mapping (a bodiless 403), as
     * this module's other slice tests do — so the {@code LOCATION_SCOPE_DENIED} assertions prove
     * the highest-precedence advice beats a parent-type handler.
     */
    @ControllerAdvice
    static class SecurityExceptionControllerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {}
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppointmentsService appointmentsService;

    @MockitoBean
    private ShopDashboardService shopDashboardService;

    @MockitoBean
    private MechanicRosterQueryService mechanicRosterQueryService;

    @MockitoBean
    private TechnicianPersonService technicianPersonService;

    @AfterEach
    void clearCaller() {
        TestSecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------------------
    // Caller shapes
    // ---------------------------------------------------------------------------------------

    /** A post-rollout token holding {@code permissions}, each OTHER-scoped to {@link #REGION_NODE}. */
    private static void asScoped(String... permissions) {
        install(caller(
                List.of(permissions),
                LocationScope.of(Set.of(), Set.of(permissions), Optional.of(Set.of(REGION_NODE)), true, RESOLVER)));
    }

    /**
     * A post-rollout token holding every permission in {@code held}, but scoped only on
     * {@code scoped} (the rest are global grants).
     */
    private static void asPartiallyScoped(List<String> held, Set<String> scoped) {
        install(caller(held, LocationScope.of(Set.of(), scoped, Optional.of(Set.of(REGION_NODE)), true, RESOLVER)));
    }

    /** A post-rollout token that carries claims but whose permissions are all global. */
    private static void asGlobal(String... permissions) {
        install(caller(
                List.of(permissions),
                LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER)));
    }

    /** A pre-rollout token: no {@code loc_*} claims at all, so no scope detail is attached. */
    private static void asPreRollout(String... permissions) {
        install(caller(List.of(permissions), null));
    }

    private static void install(Authentication caller) {
        TestSecurityContextHolder.setAuthentication(caller);
    }

    private static Authentication caller(List<String> authorities, LocationScope scope) {
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
        return token;
    }

    private static String createBody(UUID locationId) {
        return """
                {"crmCustomerId":"01960003-0000-7000-8000-000000000001",
                 "crmVehicleId":"01960003-0000-7000-8000-000000000002",
                 "locationId":"%s",
                 "startAt":"2026-09-10T08:00:00Z",
                 "endAt":"2026-09-10T10:00:00Z",
                 "serviceRequestIds":["01960003-0000-7000-8000-000000000004"]}
                """.formatted(locationId);
    }

    private static AppointmentResponse appointmentAt(UUID locationId) {
        AppointmentResponse response = new AppointmentResponse();
        response.setAppointmentId(APPOINTMENT_ID);
        response.setStatus("SCHEDULED");
        response.setLocationId(locationId);
        return response;
    }

    // ---------------------------------------------------------------------------------------
    // POST /v1/appointments — gate on the request's locationId (hasAnyAuthority)
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("AppointmentsController.createAppointment")
    class CreateAppointment {

        private static final String URL = "/v1/appointments";

        private void stubCreate(UUID locationId) {
            when(appointmentsService.createAppointment(any(), isNull(), isNull()))
                    .thenReturn(appointmentAt(locationId));
        }

        @Test
        @DisplayName("scoped caller with the location in reach answers 201")
        void scopedCallerInReach() throws Exception {
            stubCreate(SHOP_A);
            asScoped(ShopPermissions.APPOINTMENTS_CREATE);

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(createBody(SHOP_A)))
                    .andExpect(status().isCreated());

            verify(appointmentsService).createAppointment(any(), isNull(), isNull());
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void scopedCallerOutOfReach() throws Exception {
            asScoped(ShopPermissions.APPOINTMENTS_CREATE);

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(createBody(SHOP_B)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.correlationId").exists())
                    .andExpect(header().exists("X-Correlation-Id"));

            verify(appointmentsService, never()).createAppointment(any(), any(), any());
        }

        @Test
        @DisplayName("hasAnyAuthority: a scoped appointments:create is rescued by a global shop:schedule:edit")
        void heldGlobalAlternateCovers() throws Exception {
            stubCreate(SHOP_B);
            asPartiallyScoped(
                    List.of(ShopPermissions.APPOINTMENTS_CREATE, ShopPermissions.SCHEDULE_EDIT),
                    Set.of(ShopPermissions.APPOINTMENTS_CREATE));

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(createBody(SHOP_B)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("hasAnyAuthority: an alternate the caller does not hold is never consulted")
        void unheldAlternateDoesNotRescue() throws Exception {
            // shop:schedule:edit is global in the claims, but the caller does not hold it.
            asPartiallyScoped(
                    List.of(ShopPermissions.APPOINTMENTS_CREATE), Set.of(ShopPermissions.APPOINTMENTS_CREATE));

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(createBody(SHOP_B)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }

        @Test
        @DisplayName("hasAnyAuthority: both alternates held and scoped, neither covering, answers 403")
        void bothHeldNeitherCovers() throws Exception {
            asScoped(ShopPermissions.APPOINTMENTS_CREATE, ShopPermissions.SCHEDULE_EDIT);

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(createBody(SHOP_B)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }

        @Test
        @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour: 201 for any location")
        void preRolloutTokenIsUnchanged() throws Exception {
            stubCreate(SHOP_B);
            asPreRollout(ShopPermissions.APPOINTMENTS_CREATE);

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(createBody(SHOP_B)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("caller whose grant is global answers 201 for a location outside its nodes")
        void globalGrantIsNotLocationChecked() throws Exception {
            stubCreate(SHOP_B);
            asGlobal(ShopPermissions.SCHEDULE_EDIT);

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(createBody(SHOP_B)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("scoped caller naming a location the replica does not hold answers 403 (fail closed)")
        void unknownLocationDenies() throws Exception {
            asScoped(ShopPermissions.APPOINTMENTS_CREATE);

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(createBody(UNKNOWN_LOCATION)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }

        @Test
        @DisplayName("a missing locationId is a 400 for a scoped caller too — validation precedes the gate")
        void missingLocationIsBadRequestNotDenied() throws Exception {
            asScoped(ShopPermissions.APPOINTMENTS_CREATE);

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                                    {"crmCustomerId":"01960003-0000-7000-8000-000000000001",
                                     "crmVehicleId":"01960003-0000-7000-8000-000000000002",
                                     "startAt":"2026-09-10T08:00:00Z","endAt":"2026-09-10T10:00:00Z",
                                     "serviceRequestIds":["01960003-0000-7000-8000-000000000004"]}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---------------------------------------------------------------------------------------
    // GET /v1/appointments/{id} — resource-addressed sibling: 404 first, then the stored location
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("AppointmentsController.getAppointment (sibling of createAppointment)")
    class GetAppointment {

        private static final String URL = "/v1/appointments/{appointmentId}";

        @Test
        @DisplayName("scoped caller reading an appointment at a location in reach answers 200")
        void scopedCallerInReach() throws Exception {
            when(appointmentsService.getById(eq(APPOINTMENT_ID.toString()), isNull()))
                    .thenReturn(appointmentAt(SHOP_A));
            asScoped(ShopPermissions.APPOINTMENTS_VIEW);

            mockMvc.perform(get(URL, APPOINTMENT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.locationId").value(SHOP_A.toString()));
        }

        @Test
        @DisplayName("scoped caller reading an appointment at a location out of reach answers 403")
        void scopedCallerOutOfReach() throws Exception {
            when(appointmentsService.getById(eq(APPOINTMENT_ID.toString()), isNull()))
                    .thenReturn(appointmentAt(SHOP_B));
            asScoped(ShopPermissions.APPOINTMENTS_VIEW);

            mockMvc.perform(get(URL, APPOINTMENT_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }

        @Test
        @DisplayName("an unknown appointment is 404 for a scoped caller — ids cannot be probed via 403")
        void notFoundPrecedesScope() throws Exception {
            when(appointmentsService.getById(eq(APPOINTMENT_ID.toString()), isNull()))
                    .thenThrow(new AppointmentNotFoundException(APPOINTMENT_ID));
            asScoped(ShopPermissions.APPOINTMENTS_VIEW);

            mockMvc.perform(get(URL, APPOINTMENT_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
        }

        @Test
        @DisplayName("an appointment with no stored location is uncoverable by a scoped caller")
        void locationlessAppointmentDeniesScopedCaller() throws Exception {
            when(appointmentsService.getById(eq(APPOINTMENT_ID.toString()), isNull()))
                    .thenReturn(appointmentAt(null));
            asScoped(ShopPermissions.SCHEDULE_VIEW);

            mockMvc.perform(get(URL, APPOINTMENT_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }

        @Test
        @DisplayName("pre-rollout token without loc_* claims reads any appointment")
        void preRolloutTokenIsUnchanged() throws Exception {
            when(appointmentsService.getById(eq(APPOINTMENT_ID.toString()), isNull()))
                    .thenReturn(appointmentAt(SHOP_B));
            asPreRollout(ShopPermissions.SCHEDULE_VIEW);

            mockMvc.perform(get(URL, APPOINTMENT_ID)).andExpect(status().isOk());
        }
    }

    // ---------------------------------------------------------------------------------------
    // PUT /v1/appointments/{id}/reschedule, DELETE .../cancel — siblings: 404 first, then scope
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("AppointmentsController.rescheduleAppointment / cancelAppointment (siblings of createAppointment)")
    class MutateAppointment {

        private static final String RESCHEDULE_URL = "/v1/appointments/{appointmentId}/reschedule";
        private static final String CANCEL_URL = "/v1/appointments/{appointmentId}/cancel";
        private static final String RESCHEDULE_BODY = """
                {"newStartAt":"2026-09-11T08:00:00Z","newEndAt":"2026-09-11T10:00:00Z",
                 "reason":"CUSTOMER_REQUEST","notifyCustomer":true}
                """;
        private static final String CANCEL_BODY = """
                {"cancellationReason":"CUSTOMER_REQUEST"}
                """;

        private void stored(UUID locationId) {
            when(appointmentsService.getById(eq(APPOINTMENT_ID.toString()), isNull()))
                    .thenReturn(appointmentAt(locationId));
            when(appointmentsService.rescheduleAppointment(eq(APPOINTMENT_ID), any()))
                    .thenReturn(appointmentAt(locationId));
            when(appointmentsService.cancelAppointment(eq(APPOINTMENT_ID), any()))
                    .thenReturn(appointmentAt(locationId));
        }

        @Test
        @DisplayName("reschedule: scoped caller with the stored location in reach answers 200")
        void rescheduleInReach() throws Exception {
            stored(SHOP_A);
            asScoped(ShopPermissions.APPOINTMENTS_RESCHEDULE);

            mockMvc.perform(put(RESCHEDULE_URL, APPOINTMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(RESCHEDULE_BODY))
                    .andExpect(status().isOk());

            verify(appointmentsService).rescheduleAppointment(eq(APPOINTMENT_ID), any());
        }

        @Test
        @DisplayName("reschedule: scoped caller with the stored location out of reach answers 403 and nothing moves")
        void rescheduleOutOfReach() throws Exception {
            stored(SHOP_B);
            asScoped(ShopPermissions.APPOINTMENTS_RESCHEDULE);

            mockMvc.perform(put(RESCHEDULE_URL, APPOINTMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(RESCHEDULE_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(header().exists("X-Correlation-Id"));

            verify(appointmentsService, never()).rescheduleAppointment(any(), any());
        }

        @Test
        @DisplayName("reschedule: an unknown appointment is 404 for a scoped caller — 404 precedes 403")
        void rescheduleNotFoundPrecedesScope() throws Exception {
            when(appointmentsService.getById(eq(APPOINTMENT_ID.toString()), isNull()))
                    .thenThrow(new AppointmentNotFoundException(APPOINTMENT_ID));
            asScoped(ShopPermissions.APPOINTMENTS_RESCHEDULE);

            mockMvc.perform(put(RESCHEDULE_URL, APPOINTMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(RESCHEDULE_BODY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));

            verify(appointmentsService, never()).rescheduleAppointment(any(), any());
        }

        @Test
        @DisplayName("reschedule: pre-rollout token without loc_* claims moves any appointment")
        void reschedulePreRolloutUnchanged() throws Exception {
            stored(SHOP_B);
            asPreRollout(ShopPermissions.APPOINTMENTS_RESCHEDULE);

            mockMvc.perform(put(RESCHEDULE_URL, APPOINTMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(RESCHEDULE_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("cancel: scoped caller with the stored location in reach answers 200")
        void cancelInReach() throws Exception {
            stored(SHOP_A);
            asScoped(ShopPermissions.APPOINTMENTS_CANCEL);

            mockMvc.perform(delete(CANCEL_URL, APPOINTMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CANCEL_BODY))
                    .andExpect(status().isOk());

            verify(appointmentsService).cancelAppointment(eq(APPOINTMENT_ID), any());
        }

        @Test
        @DisplayName("cancel: scoped caller with the stored location out of reach answers 403 and nothing is cancelled")
        void cancelOutOfReach() throws Exception {
            stored(SHOP_B);
            asScoped(ShopPermissions.APPOINTMENTS_CANCEL);

            mockMvc.perform(delete(CANCEL_URL, APPOINTMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CANCEL_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(appointmentsService, never()).cancelAppointment(any(), any());
        }

        @Test
        @DisplayName("cancel: an unknown appointment is 404 for a scoped caller — 404 precedes 403")
        void cancelNotFoundPrecedesScope() throws Exception {
            when(appointmentsService.getById(eq(APPOINTMENT_ID.toString()), isNull()))
                    .thenThrow(new AppointmentNotFoundException(APPOINTMENT_ID));
            asScoped(ShopPermissions.APPOINTMENTS_CANCEL);

            mockMvc.perform(delete(CANCEL_URL, APPOINTMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CANCEL_BODY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));

            verify(appointmentsService, never()).cancelAppointment(any(), any());
        }

        @Test
        @DisplayName("cancel: pre-rollout token without loc_* claims cancels any appointment")
        void cancelPreRolloutUnchanged() throws Exception {
            stored(SHOP_B);
            asPreRollout(ShopPermissions.APPOINTMENTS_CANCEL);

            mockMvc.perform(delete(CANCEL_URL, APPOINTMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CANCEL_BODY))
                    .andExpect(status().isOk());
        }
    }

    // ---------------------------------------------------------------------------------------
    // GET /v1/schedules/view — gate on the locationId query parameter
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("ScheduleController.viewSchedule")
    class ViewSchedule {

        private static final String URL = "/v1/schedules/view";

        private void stubView(UUID locationId) {
            ScheduleViewResponse response = new ScheduleViewResponse();
            response.setLocationId(locationId);
            response.setDate(LocalDate.of(2026, 9, 10));
            response.setResources(List.of());
            when(appointmentsService.getScheduleView(any(ScheduleViewRequest.class), isNull()))
                    .thenReturn(response);
        }

        @Test
        @DisplayName("scoped caller with the location in reach answers 200")
        void scopedCallerInReach() throws Exception {
            stubView(SHOP_A);
            asScoped(ShopPermissions.SCHEDULE_VIEW);

            mockMvc.perform(get(URL).param("locationId", SHOP_A.toString()).param("date", "2026-09-10"))
                    .andExpect(status().isOk());

            verify(appointmentsService).getScheduleView(any(ScheduleViewRequest.class), isNull());
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void scopedCallerOutOfReach() throws Exception {
            asScoped(ShopPermissions.SCHEDULE_VIEW);

            mockMvc.perform(get(URL).param("locationId", SHOP_B.toString()).param("date", "2026-09-10"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(header().exists("X-Correlation-Id"));

            verify(appointmentsService, never()).getScheduleView(any(), any());
        }

        @Test
        @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour")
        void preRolloutTokenIsUnchanged() throws Exception {
            stubView(SHOP_B);
            asPreRollout(ShopPermissions.SCHEDULE_VIEW);

            mockMvc.perform(get(URL).param("locationId", SHOP_B.toString()).param("date", "2026-09-10"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a malformed locationId is a 400 for a scoped caller, not a 403")
        void malformedLocationIsBadRequest() throws Exception {
            asScoped(ShopPermissions.SCHEDULE_VIEW);

            mockMvc.perform(get(URL).param("locationId", "not-a-uuid").param("date", "2026-09-10"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---------------------------------------------------------------------------------------
    // GET /v1/shop-dashboard — gate on the locationId query parameter
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("ShopDashboardController.getShopDashboard")
    class GetShopDashboard {

        private static final String URL = "/v1/shop-dashboard";

        private void stubBoard(UUID locationId) {
            when(shopDashboardService.getDashboard(eq(locationId), isNull()))
                    .thenReturn(new ShopDashboardResponse(
                            locationId, LocalDate.of(2026, 9, 7), List.of(), List.of(), false));
        }

        @Test
        @DisplayName("scoped caller with the location in reach answers 200")
        void scopedCallerInReach() throws Exception {
            stubBoard(SHOP_A);
            asScoped(ShopPermissions.DASHBOARD_VIEW);

            mockMvc.perform(get(URL).param("locationId", SHOP_A.toString())).andExpect(status().isOk());
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void scopedCallerOutOfReach() throws Exception {
            asScoped(ShopPermissions.DASHBOARD_VIEW);

            mockMvc.perform(get(URL).param("locationId", SHOP_B.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(shopDashboardService, never()).getDashboard(any(), any());
        }

        @Test
        @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour")
        void preRolloutTokenIsUnchanged() throws Exception {
            stubBoard(SHOP_B);
            asPreRollout(ShopPermissions.DASHBOARD_VIEW);

            mockMvc.perform(get(URL).param("locationId", SHOP_B.toString())).andExpect(status().isOk());
        }

        @Test
        @DisplayName(
                "caller lacking the permission entirely is still the module's plain 403, not LOCATION_SCOPE_DENIED")
        void missingPermissionIsPlainForbidden() throws Exception {
            asScoped(ShopPermissions.SCHEDULE_VIEW);

            mockMvc.perform(get(URL).param("locationId", SHOP_A.toString())).andExpect(status().isForbidden());

            verify(shopDashboardService, never()).getDashboard(any(), any());
        }
    }

    // ---------------------------------------------------------------------------------------
    // GET /v1/shop-manager/{locationId}/technicians[/{personId}/person] — gate on the path
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("TechnicianController.listLocationTechnicians / getTechnicianPerson")
    class Technicians {

        private static final String LIST_URL = "/v1/shop-manager/{locationId}/technicians";
        private static final String PERSON_URL = "/v1/shop-manager/{locationId}/technicians/{personId}/person";

        @Test
        @DisplayName("list: scoped caller with the location in reach answers 200")
        void listInReach() throws Exception {
            when(mechanicRosterQueryService.listLocationTechnicians(eq(SHOP_A), isNull(), isNull(), any()))
                    .thenReturn(new PageImpl<>(List.of()));
            asScoped(ShopPermissions.TECHNICIAN_VIEW);

            mockMvc.perform(get(LIST_URL, SHOP_A)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("list: scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void listOutOfReach() throws Exception {
            asScoped(ShopPermissions.TECHNICIAN_VIEW);

            mockMvc.perform(get(LIST_URL, SHOP_B))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(mechanicRosterQueryService, never()).listLocationTechnicians(any(), any(), any(), any());
        }

        @Test
        @DisplayName("list: pre-rollout token without loc_* claims keeps today's behaviour")
        void listPreRolloutUnchanged() throws Exception {
            when(mechanicRosterQueryService.listLocationTechnicians(eq(SHOP_B), isNull(), isNull(), any()))
                    .thenReturn(new PageImpl<>(List.of()));
            asPreRollout(ShopPermissions.TECHNICIAN_VIEW);

            mockMvc.perform(get(LIST_URL, SHOP_B)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("list: a malformed path locationId is a 400 for a scoped caller, not a 403")
        void listMalformedPathIsBadRequest() throws Exception {
            asScoped(ShopPermissions.TECHNICIAN_VIEW);

            mockMvc.perform(get("/v1/shop-manager/not-a-uuid/technicians")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("person: scoped caller with the location in reach answers 200")
        void personInReach() throws Exception {
            when(technicianPersonService.getTechnicianPerson(SHOP_A, PERSON_ID))
                    .thenReturn(
                            PersonDTO.builder().id(PERSON_ID).firstName("Ada").build());
            asScoped(ShopPermissions.TECHNICIAN_VIEW);

            mockMvc.perform(get(PERSON_URL, SHOP_A, PERSON_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(PERSON_ID.toString()));
        }

        @Test
        @DisplayName("person: scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void personOutOfReach() throws Exception {
            asScoped(ShopPermissions.TECHNICIAN_VIEW);

            mockMvc.perform(get(PERSON_URL, SHOP_B, PERSON_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(technicianPersonService, never()).getTechnicianPerson(any(), any());
        }

        @Test
        @DisplayName("person: pre-rollout token without loc_* claims keeps today's behaviour")
        void personPreRolloutUnchanged() throws Exception {
            when(technicianPersonService.getTechnicianPerson(SHOP_B, PERSON_ID))
                    .thenReturn(PersonDTO.builder().id(PERSON_ID).build());
            asPreRollout(ShopPermissions.TECHNICIAN_VIEW);

            mockMvc.perform(get(PERSON_URL, SHOP_B, PERSON_ID)).andExpect(status().isOk());
        }
    }
}
