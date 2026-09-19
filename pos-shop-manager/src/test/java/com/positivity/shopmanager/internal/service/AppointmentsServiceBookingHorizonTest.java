package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.AppointmentCreation;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.shopmanager.internal.exception.BookingHorizonExceededException;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.RescheduleHistoryRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
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
 * The booking horizon on the two write paths it binds (DECISION-SHOPMGMT-019, issue #2100).
 *
 * <p>The boundary arithmetic and the facility-local counting live in {@link BookingHorizonPolicyTest};
 * what matters here is that both writes consult it, that they do so <em>before</em> anything is
 * persisted, and that a refusal is a 422-mapped policy failure rather than a validation error.
 */
@ExtendWith(MockitoExtension.class)
class AppointmentsServiceBookingHorizonTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final UUID APPOINTMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID SERVICE_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    /** 2026-03-01 + 181 days: one day past the default horizon. */
    private static final Instant BEYOND_HORIZON = Instant.parse("2026-08-29T09:00:00Z");

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

    private AppointmentsServiceImpl appointmentsService;

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
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                mock(WorkOrderAppointmentMappingRepository.class),
                mock(SchedulingConflictEvaluator.class),
                mock(SchedulingConflictRecorder.class),
                new BookingHorizonPolicy(180));
    }

    @Test
    @DisplayName("AC1: a create beyond the horizon is refused before anything is written")
    void createBeyondTheHorizonIsRefusedAndNothingIsWritten() {
        givenLocationInZone("UTC");

        AppointmentCreateRequest request = requestStartingAt(BEYOND_HORIZON);

        assertThatThrownBy(() -> appointmentsService.createAppointment(request, null, null))
                .isInstanceOf(BookingHorizonExceededException.class)
                .hasMessageContaining("180");

        verify(appointmentRepository, never()).save(any(Appointment.class));
        // The refusal happens before the replicas are consulted at all: an out-of-horizon booking
        // costs one shop lookup and nothing else.
        verify(crmSnapshotService, never()).getCustomerById(any());
        verify(sourceEligibilityService, never()).getExistingAppointmentId(any(), any(), any());
    }

    @Test
    @DisplayName("AC4: a reschedule beyond the horizon leaves the window intact and records no history")
    void rescheduleBeyondTheHorizonChangesNothing() {
        givenLocationInZone("UTC");

        Instant previousStartAt = Instant.parse("2026-03-10T10:00:00Z");
        Instant previousEndAt = Instant.parse("2026-03-10T11:00:00Z");
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(APPOINTMENT_ID);
        appointment.setLocationId(LOCATION_ID);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setStartAt(previousStartAt);
        appointment.setEndAt(previousEndAt);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));

        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(BEYOND_HORIZON);
        request.setNewEndAt(BEYOND_HORIZON.plusSeconds(3600));
        request.setReason(RescheduleReasonCode.CUSTOMER_REQUEST);

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request))
                .isInstanceOf(BookingHorizonExceededException.class);

        org.assertj.core.api.Assertions.assertThat(appointment.getStartAt()).isEqualTo(previousStartAt);
        org.assertj.core.api.Assertions.assertThat(appointment.getEndAt()).isEqualTo(previousEndAt);
        verify(appointmentRepository, never()).save(any(Appointment.class));
        // DECISION-SHOPMGMT-004: a refused reschedule must not spend one of the allowance.
        verify(rescheduleHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("A repeated Idempotency-Key replays its appointment, horizon or no horizon")
    void idempotentReplayIsNotRefusedByTheHorizon() {
        // The booking was accepted when it was made. Lowering the horizon afterwards must not turn
        // the client's retry into a 422 for a row that already exists: a replay is not a write
        // (DECISION-SHOPMGMT-014).
        Appointment existing = new Appointment();
        existing.setAppointmentId(APPOINTMENT_ID);
        existing.setLocationId(LOCATION_ID);
        existing.setStatus(AppointmentStatus.SCHEDULED);
        existing.setStartAt(BEYOND_HORIZON);
        existing.setEndAt(BEYOND_HORIZON.plusSeconds(3600));
        existing.setCrmCustomerId(CUSTOMER_ID);
        existing.setCrmVehicleId(VEHICLE_ID);
        when(appointmentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));
        // The replay only fires when the retried request matches the stored booking field for
        // field, service requests included (DECISION-SHOPMGMT-014).
        when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(APPOINTMENT_ID))
                .thenReturn(List.of(AppointmentServiceRequest.builder()
                        .serviceEntityId(SERVICE_REQUEST_ID)
                        .build()));

        AppointmentCreateRequest request = requestStartingAt(BEYOND_HORIZON);
        request.setCrmCustomerId(CUSTOMER_ID);
        request.setCrmVehicleId(VEHICLE_ID);

        AppointmentCreation creation = appointmentsService.createAppointment(request, "key-1", null);

        org.assertj.core.api.Assertions.assertThat(creation.replayed()).isTrue();
        verify(appointmentRepository, never()).save(any(Appointment.class));
    }

    @Test
    @DisplayName("AC7: an appointment already beyond the horizon stays readable")
    void legacyAppointmentBeyondTheHorizonIsStillReadable() {
        // The rule governs writes. A row booked before the horizon existed — or before it was
        // lowered — is returned normally rather than hidden or refused.
        Appointment legacy = new Appointment();
        legacy.setAppointmentId(APPOINTMENT_ID);
        legacy.setLocationId(LOCATION_ID);
        legacy.setStatus(AppointmentStatus.SCHEDULED);
        legacy.setStartAt(Instant.parse("2028-06-01T09:00:00Z"));
        legacy.setEndAt(Instant.parse("2028-06-01T10:00:00Z"));
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(legacy));

        org.assertj.core.api.Assertions.assertThat(appointmentsService.getById(APPOINTMENT_ID.toString(), null))
                .isNotNull();
    }

    private AppointmentCreateRequest requestStartingAt(Instant startAt) {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(LOCATION_ID);
        request.setCrmCustomerId(UUID.randomUUID());
        request.setCrmVehicleId(UUID.randomUUID());
        request.setStartAt(startAt);
        request.setEndAt(startAt.plusSeconds(3600));
        request.setServiceRequestIds(List.of(SERVICE_REQUEST_ID));
        return request;
    }

    private void givenLocationInZone(String zoneId) {
        Shop shop = Shop.builder()
                .id(LOCATION_ID)
                .name("Test Location")
                .address("1 Test St")
                .timezone(zoneId)
                .build();
        when(shopRepository.findById(LOCATION_ID)).thenReturn(Optional.of(shop));
    }
}
