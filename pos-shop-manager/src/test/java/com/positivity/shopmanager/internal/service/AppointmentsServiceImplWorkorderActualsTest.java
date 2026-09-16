package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.RescheduleHistoryRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
import com.positivity.shopmanager.internal.repository.WorkorderActuals;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Actual-vs-planned appointment time resolution (issue #2021 AC1-AC3, AC11) — {@code
 * AppointmentResponse.actualStartAt}/{@code actualEndAt}/{@code expectedEndAt}, resolved through
 * {@code WorkOrderAppointmentMapping} (F9), never mutating the planned {@code startAt}/{@code
 * endAt} (F2).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentsServiceImpl - workorder actuals (#2021)")
class AppointmentsServiceImplWorkorderActualsTest {

    private static final UUID APPOINTMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final Instant PLANNED_START = Instant.parse("2026-06-18T09:00:00Z");
    private static final Instant PLANNED_END = Instant.parse("2026-06-18T11:00:00Z");

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private AppointmentAuditRepository appointmentAuditRepository;

    @Mock
    private RescheduleHistoryRepository rescheduleHistoryRepository;

    @Mock
    private AppointmentServiceRequestRepository appointmentServiceRequestRepository;

    @Mock
    private AppointmentLoadService appointmentLoadService;

    @Mock
    private CrmSnapshotService crmSnapshotService;

    @Mock
    private StaffingScheduleService staffingScheduleService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private SourceEligibilityService sourceEligibilityService;

    @Mock
    private WorkOrderAppointmentMappingRepository workOrderAppointmentMappingRepository;

    private AppointmentsServiceImpl appointmentsService;

    private final SchedulingConflictEvaluator conflictEvaluator =
            org.mockito.Mockito.mock(SchedulingConflictEvaluator.class);
    private final SchedulingConflictRecorder conflictRecorder =
            org.mockito.Mockito.mock(SchedulingConflictRecorder.class);

    @BeforeEach
    void setUp() {
        appointmentsService = new AppointmentsServiceImpl(
                appointmentRepository,
                appointmentAuditRepository,
                rescheduleHistoryRepository,
                appointmentServiceRequestRepository,
                new ObjectMapper(),
                appointmentLoadService,
                crmSnapshotService,
                staffingScheduleService,
                eventPublisher,
                shopRepository,
                sourceEligibilityService,
                mock(ExtPersonReplicaRepository.class),
                Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneOffset.UTC),
                workOrderAppointmentMappingRepository,
                conflictEvaluator,
                conflictRecorder);

        when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(APPOINTMENT_ID))
                .thenReturn(List.<AppointmentServiceRequest>of());
    }

    @Test
    @DisplayName("AC1 - a job finishing early exposes actualStartAt/actualEndAt without moving the planned window")
    void getById_exposesActuals_whenWorkorderFinishedEarly() {
        Appointment appointment = buildAppointment(AppointmentStatus.QUALITY_CHECK, PLANNED_START, PLANNED_END);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));

        Instant actualStart = Instant.parse("2026-06-18T09:05:00Z");
        Instant actualEnd = Instant.parse("2026-06-18T10:40:00Z"); // finished 20 min early
        stubActuals(new WorkorderActuals(APPOINTMENT_ID, WORKORDER_ID, actualStart, actualEnd, null));

        AppointmentResponse response = appointmentsService.getById(APPOINTMENT_ID.toString(), null);

        assertThat(response.getStartAt()).isEqualTo(PLANNED_START);
        assertThat(response.getEndAt()).isEqualTo(PLANNED_END);
        assertThat(response.getActualStartAt()).isEqualTo(actualStart);
        assertThat(response.getActualEndAt()).isEqualTo(actualEnd);
        assertThat(response.getExpectedEndAt()).isNull();
    }

    @Test
    @DisplayName(
            "AC1 - a job finishing late within the day still reports its real actualEndAt, planned endAt unchanged")
    void getById_exposesActuals_whenWorkorderFinishedLate() {
        Appointment appointment = buildAppointment(AppointmentStatus.QUALITY_CHECK, PLANNED_START, PLANNED_END);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));

        Instant actualStart = Instant.parse("2026-06-18T09:05:00Z");
        Instant actualEnd = Instant.parse("2026-06-18T11:40:00Z"); // 40 min over planned
        stubActuals(new WorkorderActuals(APPOINTMENT_ID, WORKORDER_ID, actualStart, actualEnd, null));

        AppointmentResponse response = appointmentsService.getById(APPOINTMENT_ID.toString(), null);

        assertThat(response.getEndAt()).isEqualTo(PLANNED_END);
        assertThat(response.getActualEndAt()).isEqualTo(actualEnd);
    }

    @Test
    @DisplayName("AC3 - a job still running exposes actualStartAt with a null actualEndAt, planned endAt intact")
    void getById_exposesRunningJob_withNullActualEnd() {
        Appointment appointment = buildAppointment(AppointmentStatus.WORK_IN_PROGRESS, PLANNED_START, PLANNED_END);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));

        Instant actualStart = Instant.parse("2026-06-18T09:05:00Z");
        stubActuals(new WorkorderActuals(APPOINTMENT_ID, WORKORDER_ID, actualStart, null, null));

        AppointmentResponse response = appointmentsService.getById(APPOINTMENT_ID.toString(), null);

        assertThat(response.getActualStartAt()).isEqualTo(actualStart);
        assertThat(response.getActualEndAt()).isNull();
        assertThat(response.getEndAt()).isEqualTo(PLANNED_END);
        assertThat(response.getStatus()).isEqualTo("WORK_IN_PROGRESS");
        // A caller can now state "N minutes over planned" purely from actualStartAt, endAt and
        // status, without this endpoint ever synthesising expectedEndAt (#2021 F5).
        assertThat(response.getExpectedEndAt()).isNull();
    }

    @Test
    @DisplayName("no linked workorder: all three actual fields are null, and resolution does not error")
    void getById_returnsNullActuals_whenNoLinkedWorkorder() {
        Appointment appointment = buildAppointment(AppointmentStatus.SCHEDULED, PLANNED_START, PLANNED_END);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));
        when(workOrderAppointmentMappingRepository.findActualsByAppointmentIds(List.of(APPOINTMENT_ID)))
                .thenReturn(List.of());

        AppointmentResponse response = appointmentsService.getById(APPOINTMENT_ID.toString(), null);

        assertThat(response.getActualStartAt()).isNull();
        assertThat(response.getActualEndAt()).isNull();
        assertThat(response.getExpectedEndAt()).isNull();
    }

    @Test
    @DisplayName("linked workorder whose workStartedAt is still null resolves to null actuals, not an error")
    void getById_returnsNullActuals_whenWorkorderNotStartedYet() {
        Appointment appointment = buildAppointment(AppointmentStatus.SCHEDULED, PLANNED_START, PLANNED_END);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));
        stubActuals(new WorkorderActuals(APPOINTMENT_ID, WORKORDER_ID, null, null, null));

        AppointmentResponse response = appointmentsService.getById(APPOINTMENT_ID.toString(), null);

        assertThat(response.getActualStartAt()).isNull();
        assertThat(response.getActualEndAt()).isNull();
    }

    @Test
    @DisplayName("AC2 - a reschedule moves the planned window and leaves the actuals untouched")
    void rescheduleAppointment_movesPlannedWindow_leavesActualsUnaffected() {
        Appointment appointment = buildAppointment(AppointmentStatus.SCHEDULED, PLANNED_START, PLANNED_END);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Instant actualStart = Instant.parse("2026-06-18T09:05:00Z");
        Instant actualEnd = Instant.parse("2026-06-18T10:40:00Z");
        stubActuals(new WorkorderActuals(APPOINTMENT_ID, WORKORDER_ID, actualStart, actualEnd, null));

        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        Instant newStart = Instant.parse("2026-06-19T09:00:00Z");
        Instant newEnd = Instant.parse("2026-06-19T11:00:00Z");
        request.setNewStartAt(newStart);
        request.setNewEndAt(newEnd);
        request.setReason(RescheduleReasonCode.CUSTOMER_REQUEST);

        AppointmentResponse response = appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request);

        assertThat(response.getStartAt()).isEqualTo(newStart);
        assertThat(response.getEndAt()).isEqualTo(newEnd);
        // The actuals resolved from the (unchanged) linked workorder do not move with the reschedule.
        assertThat(response.getActualStartAt()).isEqualTo(actualStart);
        assertThat(response.getActualEndAt()).isEqualTo(actualEnd);
    }

    private void stubActuals(WorkorderActuals actuals) {
        when(workOrderAppointmentMappingRepository.findActualsByAppointmentIds(eq(List.of(APPOINTMENT_ID))))
                .thenReturn(List.of(actuals));
    }

    private Appointment buildAppointment(AppointmentStatus status, Instant startAt, Instant endAt) {
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(APPOINTMENT_ID);
        appointment.setStatus(status);
        appointment.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        appointment.setResourceId("BAY-01");
        appointment.setCrmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000009"));
        appointment.setCrmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000011"));
        appointment.setStartAt(startAt);
        appointment.setEndAt(endAt);
        appointment.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        return appointment;
    }
}
