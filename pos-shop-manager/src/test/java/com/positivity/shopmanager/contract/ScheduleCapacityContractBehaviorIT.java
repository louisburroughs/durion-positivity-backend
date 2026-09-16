package com.positivity.shopmanager.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.BaseContractIntegrationTest;
import com.positivity.shopmanager.PosShopManagerApplication;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
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
 * Contract behavioral integration tests for issue #2023: {@code GET /v1/schedules/capacity}.
 *
 * <p>Follows the shape of {@link ScheduleViewContractBehaviorIT} — separate in-memory database, the
 * same {@code withGatewayAuth}/{@code defaultAuthorities} harness — but exercises the range read
 * rather than the single-day board.
 */
@SpringBootTest(classes = PosShopManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:shopmgr_sched_capacity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
@Tag("contract")
@DisplayName("Issue #2023: GET /v1/schedules/capacity — Contract Behavioral Tests")
class ScheduleCapacityContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID LOCATION_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** A Monday, so weekday operating hours below apply without further arithmetic. */
    private static final String MONDAY_DATE = "2026-10-05";

    private static final String WEEKDAY_HOURS = """
            [{"dayOfWeek":"MONDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"TUESDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"WEDNESDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"THURSDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"FRIDAY","openTime":"08:00:00","closeTime":"17:00:00"}]""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Autowired
    private ExtBayReplicaRepository extBayReplicaRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    // Mocked to prevent context-startup failures; not invoked by capacity operations.
    @MockitoBean
    private com.positivity.shopmanager.internal.service.CrmSnapshotService crmSnapshotService;

    @BeforeEach
    void cleanDatabase() {
        appointmentRepository.deleteAll();
        extBayReplicaRepository.deleteAll();
        extLocationReplicaRepository.deleteAll();

        extLocationReplicaRepository.save(ExtLocationReplica.builder()
                .locationId(LOCATION_ID)
                .code("CAP-1")
                .name("Capacity Test Location")
                .active(true)
                .aggregateVersion(1)
                .syncedAt(Instant.now())
                .timezone("UTC")
                .operatingHours(WEEKDAY_HOURS)
                .build());
    }

    @Override
    protected String defaultAuthorities() {
        return "shop:schedule:view";
    }

    // ─── SC1: Happy path ───────────────────────────────────────────────────────

    @Test
    @DisplayName("SC1: a one-day range returns the location, timezone and a bay listed with zero occupancy")
    void should_return_200_with_bay_roster_for_a_one_day_range() throws Exception {
        UUID bayId = UUIDv7Generator.generate();
        extBayReplicaRepository.save(ExtBayReplica.builder()
                .bayId(bayId)
                .locationId(LOCATION_ID)
                .name("Bay 1")
                .active(true)
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .build());
        appointmentRepository.save(Appointment.builder()
                .status(AppointmentStatus.SCHEDULED)
                .locationId(LOCATION_ID)
                .resourceId(bayId.toString())
                .resourceType("BAY")
                .crmCustomerId(CUSTOMER_ID)
                .crmVehicleId(VEHICLE_ID)
                .startAt(Instant.parse("2026-10-05T10:00:00Z"))
                .endAt(Instant.parse("2026-10-05T12:00:00Z"))
                .build());

        mockMvc.perform(withGatewayAuth(get("/v1/schedules/capacity")
                        .param("locationId", LOCATION_ID.toString())
                        .param("from", MONDAY_DATE)
                        .param("to", MONDAY_DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.days").isArray())
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].status").value("OK"))
                .andExpect(jsonPath("$.days[0].bays[0].bayId").value(bayId.toString()))
                .andExpect(jsonPath("$.days[0].bays[0].occupiedMinutes").value(120));
    }

    // ─── SC2: empty bay still listed (AC9) ──────────────────────────────────────

    @Test
    @DisplayName("SC2: a bay with zero appointments is still listed, with occupiedMinutes 0")
    void should_return_200_with_zero_occupied_minutes_for_an_empty_bay() throws Exception {
        UUID bayId = UUIDv7Generator.generate();
        extBayReplicaRepository.save(ExtBayReplica.builder()
                .bayId(bayId)
                .locationId(LOCATION_ID)
                .name("Empty Bay")
                .active(true)
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .build());

        mockMvc.perform(withGatewayAuth(get("/v1/schedules/capacity")
                        .param("locationId", LOCATION_ID.toString())
                        .param("from", MONDAY_DATE)
                        .param("to", MONDAY_DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].bays[0].occupiedMinutes").value(0));
    }

    // ─── SC3: to before from (400) ───────────────────────────────────────────────

    @Test
    @DisplayName("SC3: to before from is a 400 Bad Request")
    void should_return_400_when_to_is_before_from() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/schedules/capacity")
                        .param("locationId", LOCATION_ID.toString())
                        .param("from", "2026-10-05")
                        .param("to", "2026-10-01")))
                .andExpect(status().isBadRequest());
    }

    // ─── SC4: span over 42 days (422) ────────────────────────────────────────────

    @Test
    @DisplayName("SC4: a 43-day span is 422 CAPACITY_RANGE_EXCEEDED")
    void should_return_422_when_range_exceeds_policy_limit() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/schedules/capacity")
                        .param("locationId", LOCATION_ID.toString())
                        .param("from", "2026-01-01")
                        .param("to", "2026-02-13")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAPACITY_RANGE_EXCEEDED"));
    }

    // ─── SC5: exactly 42 days is accepted (AC1) ──────────────────────────────────

    @Test
    @DisplayName("SC5: an exactly-42-day span is accepted and returns 42 days in one call")
    void should_return_200_with_42_days_for_a_42_day_range() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/schedules/capacity")
                        .param("locationId", LOCATION_ID.toString())
                        .param("from", "2026-10-01")
                        .param("to", "2026-11-11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(42));
    }

    // ─── SC6: missing required params (400) ──────────────────────────────────────

    @Test
    @DisplayName("SC6: a missing locationId query param is a 400 Bad Request")
    void should_return_400_when_locationId_is_missing() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        get("/v1/schedules/capacity").param("from", MONDAY_DATE).param("to", MONDAY_DATE)))
                .andExpect(status().isBadRequest());
    }

    // ─── SC7: a date with no operatingHours entry is CLOSED, never omitted ───────

    @Test
    @DisplayName("SC7: a weekend date with no operatingHours entry is CLOSED with no bays")
    void should_return_200_with_closed_status_for_a_date_with_no_hours_entry() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/schedules/capacity")
                        .param("locationId", LOCATION_ID.toString())
                        .param("from", "2026-10-10") // Saturday: no entry in WEEKDAY_HOURS
                        .param("to", "2026-10-10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.days[0].bays").isArray())
                .andExpect(jsonPath("$.days[0].bays").isEmpty());
    }
}
