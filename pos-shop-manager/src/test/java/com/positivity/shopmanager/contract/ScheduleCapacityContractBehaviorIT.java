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
import com.positivity.shopmanager.internal.entity.ExtWorkorderReplica;
import com.positivity.shopmanager.internal.entity.WorkOrderAppointmentMapping;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
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

    /** The Tuesday after {@link #MONDAY_DATE}, for the #2050 pre-range carry-over scenario. */
    private static final String TUESDAY_DATE = "2026-10-06";

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

    @Autowired
    private WorkOrderAppointmentMappingRepository workOrderAppointmentMappingRepository;

    @Autowired
    private ExtWorkorderReplicaRepository extWorkorderReplicaRepository;

    // Mocked to prevent context-startup failures; not invoked by capacity operations.
    @MockitoBean
    private com.positivity.shopmanager.internal.service.CrmSnapshotService crmSnapshotService;

    @BeforeEach
    void cleanDatabase() {
        // Mappings first: work_order_appointment_mapping carries the FK onto appointment.
        workOrderAppointmentMappingRepository.deleteAll();
        extWorkorderReplicaRepository.deleteAll();
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
        // Built the way AppointmentsServiceImpl#persistAppointment actually creates a row:
        // resourceId set, resourceType left null (#2023 F1 — production never writes
        // Appointment.resourceType; a fixture that sets it asserts a shape production cannot
        // produce). Bay membership must resolve from resourceId alone against the active bay
        // roster, which is exactly what this test proves.
        appointmentRepository.save(Appointment.builder()
                .status(AppointmentStatus.SCHEDULED)
                .locationId(LOCATION_ID)
                .resourceId(bayId.toString())
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

    // ─── SC8: a job planned before the range whose actuals overrun into it (#2050) ──

    @Test
    @DisplayName("SC8: a job planned before the range whose actuals overrun into it holds the bay on "
            + "the range's first day, and carryOverIn names the earlier date (#2050 AC1)")
    void should_return_200_with_carry_over_from_a_job_planned_before_the_range() throws Exception {
        UUID bayId = UUIDv7Generator.generate();
        extBayReplicaRepository.save(ExtBayReplica.builder()
                .bayId(bayId)
                .locationId(LOCATION_ID)
                .name("Bay 1")
                .active(true)
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .build());
        // Planned Monday 15:00-17:00 — entirely before the Tuesday-only range requested below.
        Appointment appointment = appointmentRepository.save(Appointment.builder()
                .status(AppointmentStatus.SCHEDULED)
                .locationId(LOCATION_ID)
                .resourceId(bayId.toString())
                .crmCustomerId(CUSTOMER_ID)
                .crmVehicleId(VEHICLE_ID)
                .startAt(Instant.parse("2026-10-05T15:00:00Z"))
                .endAt(Instant.parse("2026-10-05T17:00:00Z"))
                .build());
        // ...but actually running from Monday 15:00 until Tuesday 11:00, per the ext_workorder
        // replica this module reads instead of calling pos-workorder (ADR-0044 §6).
        WorkOrderAppointmentMapping mapping = workOrderAppointmentMappingRepository.save(
                WorkOrderAppointmentMapping.builder().appointment(appointment).build());
        extWorkorderReplicaRepository.save(ExtWorkorderReplica.builder()
                .workorderId(mapping.getWorkOrderId())
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .workStartedAt(Instant.parse("2026-10-05T15:00:00Z"))
                .completedAt(Instant.parse("2026-10-06T11:00:00Z"))
                .build());

        mockMvc.perform(withGatewayAuth(get("/v1/schedules/capacity")
                        .param("locationId", LOCATION_ID.toString())
                        .param("from", TUESDAY_DATE)
                        .param("to", TUESDAY_DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].date").value(TUESDAY_DATE))
                .andExpect(jsonPath("$.days[0].status").value("OK"))
                // Tuesday 08:00-11:00 is held by Monday's overrun, not free.
                .andExpect(jsonPath("$.days[0].bays[0].occupiedMinutes").value(180))
                .andExpect(jsonPath("$.days[0].bays[0].carryOverIn.length()").value(1))
                .andExpect(jsonPath("$.days[0].bays[0].carryOverIn[0].fromDate").value(MONDAY_DATE))
                .andExpect(jsonPath("$.days[0].bays[0].carryOverIn[0].appointmentId")
                        .value(appointment.getAppointmentId().toString()))
                .andExpect(jsonPath("$.days[0].bays[0].carryOverIn[0].workorderId")
                        .value(mapping.getWorkOrderId().toString()))
                .andExpect(jsonPath("$.days[0].bays[0].carryOverIn[0].bayHours").value(3.0));
    }
}
