package com.positivity.shopmgmt.cap137;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.PosShopManagerApplication;
import com.positivity.shopmanager.internal.client.CrmCustomerClient;
import com.positivity.shopmanager.internal.client.CrmVehicleClient;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmgmt.BaseContractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract behavioral integration tests for CAP-137 Story #74:
 * View Schedule by Location and Resource.
 *
 * <p>
 * Validates the HTTP REST contract for:
 * <ul>
 * <li>GET /v1/schedules/view?locationId=&amp;date=YYYY-MM-DD</li>
 * </ul>
 *
 * <p>
 * Assertions align with:
 * <ul>
 * <li>ADR-0017: HTTP response code matrix (200/400/404)</li>
 * <li>ADR-0018: Audit actor fields sourced from security context (X-User
 * header)</li>
 * </ul>
 *
 * Issue: #74
 */
@SpringBootTest(classes = PosShopManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Issue #74: H2 in-memory datasource + disable external startup dependencies
// for isolated integration tests
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shopmgr_sched_view;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.config.enabled=false",
        "spring.boot.admin.client.enabled=false",
        "pos.security.permission-registration.enabled=false"
})
// Issue #74 end
@Tag("contract")
@DisplayName("CAP-137 Story #74: View Schedule by Location and Resource — Contract Behavioral Tests")
class ScheduleViewContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID TEST_LOCATION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_LOCATION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID UNKNOWN_LOCATION_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID TEST_CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEST_VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /**
     * Date used as the schedule view query target; all test appointments fall
     * within this UTC day.
     */
    private static final String TEST_DATE = "2026-06-01";

    /** Start within 2026-06-01 UTC: 09:00. */
    private static final Instant DAY_09_00 = Instant.parse("2026-06-01T09:00:00Z");
    /** End within 2026-06-01 UTC: 10:00. */
    private static final Instant DAY_10_00 = Instant.parse("2026-06-01T10:00:00Z");
    /** Mid-overlap start within 2026-06-01 UTC: 09:30. */
    private static final Instant DAY_09_30 = Instant.parse("2026-06-01T09:30:00Z");
    /** Mid-overlap end within 2026-06-01 UTC: 10:30. */
    private static final Instant DAY_10_30 = Instant.parse("2026-06-01T10:30:00Z");
    /** Non-overlapping window: 11:00. */
    private static final Instant DAY_11_00 = Instant.parse("2026-06-01T11:00:00Z");
    /** Non-overlapping window: 12:00. */
    private static final Instant DAY_12_00 = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private AppointmentAuditRepository appointmentAuditRepository;

    @Autowired
    private ShopRepository shopRepository;

    // Mocked to prevent context-startup failures; not invoked by schedule view
    // operations
    @MockitoBean
    private CrmCustomerClient crmCustomerClient;

    @MockitoBean
    private CrmVehicleClient crmVehicleClient;

    /**
     * Wipes appointment and audit tables before each test to ensure full isolation.
     * Audit records are deleted first to satisfy FK ordering constraints.
     */
    @BeforeEach
    void cleanDatabase() {
        appointmentAuditRepository.deleteAll();
        appointmentRepository.deleteAll();
        shopRepository.deleteAll();

        shopRepository.save(Shop.builder()
                .id(TEST_LOCATION_ID)
                .name("Test Location")
                .address("123 Test St")
                .timezone("UTC")
                .build());
        shopRepository.save(Shop.builder()
                .id(OTHER_LOCATION_ID)
                .name("Other Location")
                .address("456 Other St")
                .timezone("UTC")
                .build());
    }

    @Override
    protected String defaultAuthorities() {
        return "shop:schedule:view";
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Persists a SCHEDULED appointment with the given resource details and time
     * window.
     *
     * @param locationId   the location the appointment belongs to
     * @param resourceId   the resource (e.g. bay) identifier
     * @param resourceType the resource type (e.g. "BAY", "TECHNICIAN")
     * @param startAt      appointment start instant
     * @param endAt        appointment end instant
     * @return the persisted {@link Appointment} with generated appointmentId
     */
    private Appointment saveAppointment(UUID locationId, String resourceId, String resourceType,
            Instant startAt, Instant endAt) {
        Appointment appt = Appointment.builder()
                .crmCustomerId(TEST_CUSTOMER_ID)
                .crmVehicleId(TEST_VEHICLE_ID)
                .locationId(locationId)
                .resourceId(resourceId)
                .resourceType(resourceType)
                .startAt(startAt)
                .endAt(endAt)
                .status(AppointmentStatus.SCHEDULED)
                .build();
        return appointmentRepository.save(appt);
    }

    // ─── SV1: Happy path ────────────────────────────────────────────────────────

    /**
     * SV1: Happy path — valid locationId + date returns 200 OK with a resources
     * array.
     * Currently fails with 501 because getScheduleView throws
     * UnsupportedOperationException.
     */
    @Test
    @DisplayName("SV1: should_return_200_with_resources_array_for_valid_locationId_and_date")
    void should_return_200_with_resources_array_for_valid_locationId_and_date() throws Exception {
        // Issue #74: set up a known appointment so the response has at least one
        // resource entry
        saveAppointment(TEST_LOCATION_ID, "BAY-01", "BAY", DAY_09_00, DAY_10_00);

        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("locationId", TEST_LOCATION_ID.toString())
                        .param("date", TEST_DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(TEST_LOCATION_ID.toString()))
                .andExpect(jsonPath("$.date").value(TEST_DATE))
                .andExpect(jsonPath("$.resources").isArray());
    }

    // ─── SV2: No events ─────────────────────────────────────────────────────────

    /**
     * SV2: No events for location/date — 200 OK with empty resources array.
     * Currently fails with 501 because getScheduleView throws
     * UnsupportedOperationException.
     */
    @Test
    @DisplayName("SV2: should_return_200_with_empty_resources_when_no_appointments_exist")
    void should_return_200_with_empty_resources_when_no_appointments_exist() throws Exception {
        // Issue #74: DB is clean; no appointments saved for this location+date
        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("locationId", TEST_LOCATION_ID.toString())
                        .param("date", TEST_DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(TEST_LOCATION_ID.toString()))
                .andExpect(jsonPath("$.resources").isArray())
                .andExpect(jsonPath("$.resources").isEmpty());
    }

    // ─── SV3: resourceType filter ───────────────────────────────────────────────

    /**
     * SV3: Filter by resourceType — returns 200 with only resources matching the
     * requested type.
     * Currently fails with 501 because getScheduleView throws
     * UnsupportedOperationException.
     */
    @Test
    @DisplayName("SV3: should_return_200_with_only_matching_resourceType_when_filter_applied")
    void should_return_200_with_only_matching_resourceType_when_filter_applied() throws Exception {
        // Issue #74: save one BAY and one TECHNICIAN appointment
        saveAppointment(TEST_LOCATION_ID, "BAY-01", "BAY", DAY_09_00, DAY_10_00);
        saveAppointment(TEST_LOCATION_ID, "TECH-01", "TECHNICIAN", DAY_09_00, DAY_10_00);

        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("locationId", TEST_LOCATION_ID.toString())
                        .param("date", TEST_DATE)
                        .param("resourceType", "BAY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources").isArray())
                .andExpect(jsonPath("$.resources[*].resourceType").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("BAY"))));
    }

    // ─── SV4: Missing locationId (400) ──────────────────────────────────────────

    /**
     * SV4: Missing locationId query parameter → 400 Bad Request.
     * Spring MVC rejects the request before the controller method is invoked.
     */
    @Test
    @DisplayName("SV4: should_return_400_when_locationId_query_param_is_missing")
    void should_return_400_when_locationId_query_param_is_missing() throws Exception {
        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("date", TEST_DATE)))
                .andExpect(status().isBadRequest());
    }

    // ─── SV5: Missing date (400) ─────────────────────────────────────────────────

    /**
     * SV5: Missing date query parameter → 400 Bad Request.
     * Spring MVC rejects the request before the controller method is invoked.
     */
    @Test
    @DisplayName("SV5: should_return_400_when_date_query_param_is_missing")
    void should_return_400_when_date_query_param_is_missing() throws Exception {
        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("locationId", TEST_LOCATION_ID.toString())))
                .andExpect(status().isBadRequest());
    }

    // ─── SV6: Invalid date format (400) ──────────────────────────────────────────

    /**
     * SV6: Invalid date format → 400 Bad Request.
     * Spring MVC fails to bind "not-a-date" to LocalDate and rejects the request.
     */
    @Test
    @DisplayName("SV6: should_return_400_when_date_format_is_invalid")
    void should_return_400_when_date_format_is_invalid() throws Exception {
        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("locationId", TEST_LOCATION_ID.toString())
                        .param("date", "not-a-date")))
                .andExpect(status().isBadRequest());
    }

    // ─── SV7: Non-existent locationId (404) ──────────────────────────────────────

    /**
     * SV7: Non-existent locationId → 404 Not Found with error code
     * LOCATION_NOT_FOUND.
     * Currently fails with 501 because getScheduleView throws
     * UnsupportedOperationException.
     */
    @Test
    @DisplayName("SV7: should_return_404_LOCATION_NOT_FOUND_when_locationId_does_not_exist")
    void should_return_404_LOCATION_NOT_FOUND_when_locationId_does_not_exist() throws Exception {
        // Issue #74: no appointments in DB for UNKNOWN_LOCATION_ID
        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("locationId", UNKNOWN_LOCATION_ID.toString())
                        .param("date", TEST_DATE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCATION_NOT_FOUND"));
    }

    // ─── SV8: Conflict detected
    // ───────────────────────────────────────────────────

    /**
     * SV8: Two appointments on the same resourceId and locationId with overlapping
     * times
     * → hasConflict=true, severity=BLOCKING, conflictingEventIds populated and
     * mutual.
     * Currently fails with 501 because getScheduleView throws
     * UnsupportedOperationException.
     */
    @Test
    @DisplayName("SV8: should_return_hasConflict_true_with_BLOCKING_severity_when_appointments_overlap")
    void should_return_hasConflict_true_with_BLOCKING_severity_when_appointments_overlap() throws Exception {
        // Issue #74: two appointments on BAY-01 at TEST_LOCATION_ID with overlapping
        // windows
        // A: 09:00–10:00, B: 09:30–10:30 → overlapping 09:30–10:00
        saveAppointment(TEST_LOCATION_ID, "BAY-01", "BAY", DAY_09_00, DAY_10_00);
        saveAppointment(TEST_LOCATION_ID, "BAY-01", "BAY", DAY_09_30, DAY_10_30);

        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("locationId", TEST_LOCATION_ID.toString())
                        .param("date", TEST_DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources").isArray())
                .andExpect(jsonPath("$.resources[0].events[0].hasConflict").value(true))
                .andExpect(jsonPath("$.resources[0].events[0].severity").value("BLOCKING"))
                .andExpect(jsonPath("$.resources[0].events[0].conflictDetails.conflictingEventIds").isArray())
                .andExpect(jsonPath("$.resources[0].events[0].conflictDetails.conflictingEventIds",
                        org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.resources[0].events[0].conflictDetails.conflictingEventIds[0]").isNotEmpty())
                .andExpect(jsonPath("$.resources[0].events[1].hasConflict").value(true))
                .andExpect(jsonPath("$.resources[0].events[1].severity").value("BLOCKING"))
                .andExpect(jsonPath("$.resources[0].events[1].conflictDetails.conflictingEventIds").isArray())
                .andExpect(jsonPath("$.resources[0].events[1].conflictDetails.conflictingEventIds",
                        org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.resources[0].events[1].conflictDetails.conflictingEventIds[0]").isNotEmpty());
    }

    // ─── SV9: No conflict
    // ─────────────────────────────────────────────────────────

    /**
     * SV9: Two appointments on the same resourceId but non-overlapping times →
     * hasConflict=false.
     * Currently fails with 501 because getScheduleView throws
     * UnsupportedOperationException.
     */
    @Test
    @DisplayName("SV9: should_return_hasConflict_false_when_appointments_do_not_overlap")
    void should_return_hasConflict_false_when_appointments_do_not_overlap() throws Exception {
        // Issue #74: two appointments on BAY-01 with non-overlapping windows
        // A: 09:00–10:00, B: 11:00–12:00 → no overlap
        saveAppointment(TEST_LOCATION_ID, "BAY-01", "BAY", DAY_09_00, DAY_10_00);
        saveAppointment(TEST_LOCATION_ID, "BAY-01", "BAY", DAY_11_00, DAY_12_00);

        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("locationId", TEST_LOCATION_ID.toString())
                        .param("date", TEST_DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources").isArray())
                .andExpect(jsonPath(
                        "$.resources[?(@.resourceId == 'BAY-01')].events[*].hasConflict").value(
                                org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))));
    }

    // ─── SV10: HR overlay unavailable ────────────────────────────────────────────

    /**
     * SV10: HR availability overlay requested but unavailable → still 200 OK with
     * availabilityOverlayStatus=UNAVAILABLE and warnings containing
     * HR_SYSTEM_UNAVAILABLE.
     * Currently fails with 501 because getScheduleView throws
     * UnsupportedOperationException.
     */
    @Test
    @DisplayName("SV10: should_return_200_with_UNAVAILABLE_overlay_status_when_hr_system_is_down")
    void should_return_200_with_UNAVAILABLE_overlay_status_when_hr_system_is_down() throws Exception {
        // Issue #74: includeAvailabilityOverlay=true triggers HR overlay attempt
        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("locationId", TEST_LOCATION_ID.toString())
                        .param("date", TEST_DATE)
                        .param("includeAvailabilityOverlay", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availabilityOverlayStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.warnings").value(
                        org.hamcrest.Matchers.hasItem("HR_SYSTEM_UNAVAILABLE")));
    }

    // ─── SV11: Unknown resourceId (404) ──────────────────────────────────────────

    /**
     * SV11: Filter by resourceId with a value that exists in no appointment → 404
     * Not Found.
     * Currently fails with 501 because getScheduleView throws
     * UnsupportedOperationException.
     */
    @Test
    @DisplayName("SV11: should_return_404_when_resourceId_filter_matches_no_resource")
    void should_return_404_when_resourceId_filter_matches_no_resource() throws Exception {
        // Issue #74: no appointments with resourceId=UNKNOWN-BAY in DB
        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("locationId", TEST_LOCATION_ID.toString())
                        .param("date", TEST_DATE)
                        .param("resourceId", "UNKNOWN-BAY")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ─── SV12: resourceId filter
    // ──────────────────────────────────────────────────

    /**
     * SV12: Filter by resourceId — valid resourceId returns 200 with only events
     * for that resource.
     * Currently fails with 501 because getScheduleView throws
     * UnsupportedOperationException.
     */
    @Test
    @DisplayName("SV12: should_return_200_with_only_events_for_requested_resourceId")
    void should_return_200_with_only_events_for_requested_resourceId() throws Exception {
        // Issue #74: save two appointments on different resources at TEST_LOCATION_ID
        saveAppointment(TEST_LOCATION_ID, "BAY-01", "BAY", DAY_09_00, DAY_10_00);
        saveAppointment(TEST_LOCATION_ID, "BAY-02", "BAY", DAY_11_00, DAY_12_00);

        mockMvc.perform(withGatewayAuth(
                get("/v1/schedules/view")
                        .param("locationId", TEST_LOCATION_ID.toString())
                        .param("date", TEST_DATE)
                        .param("resourceId", "BAY-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources").isArray())
                .andExpect(jsonPath("$.resources[*].resourceId").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("BAY-01"))));
    }
}
