package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.AppointmentCreation;
import com.positivity.shopmanager.internal.entity.ConflictRule;
import com.positivity.shopmanager.internal.enums.ConflictResourceType;
import com.positivity.shopmanager.internal.enums.ConflictSeverity;
import com.positivity.shopmanager.internal.exception.KeylessDuplicateReplayException;
import com.positivity.shopmanager.internal.exception.SchedulingConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.CancelAppointmentRequest;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.CancellationReasonCode;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.shopmanager.internal.event.AppointmentCreatedEvent;
import com.positivity.shopmanager.internal.exception.AppointmentStateException;
import com.positivity.shopmanager.internal.exception.AppointmentValidationException;
import com.positivity.shopmanager.internal.exception.CrmCustomerNotFoundException;
import com.positivity.shopmanager.internal.exception.CrmVehicleNotFoundException;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class AppointmentsServiceNewBehaviorsTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

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
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private SourceEligibilityService sourceEligibilityService;

    // F5 fix: manual constructor injection so Clock is never null
    private AppointmentsServiceImpl appointmentsService;

    private UUID appointmentId;
    private Appointment appointment;

    private final SchedulingConflictEvaluator conflictEvaluator =
            org.mockito.Mockito.mock(SchedulingConflictEvaluator.class);
    private final SchedulingConflictRecorder conflictRecorder =
            org.mockito.Mockito.mock(SchedulingConflictRecorder.class);

    @BeforeEach
    void setUp() {
        // F5 fix: construct service explicitly with a fixed Clock — mirrors the
        // pattern used in AppointmentsServiceImplTest to avoid null-Clock injection
        appointmentsService = new AppointmentsServiceImpl(
                appointmentRepository,
                appointmentAuditRepository,
                rescheduleHistoryRepository,
                appointmentServiceRequestRepository,
                new ObjectMapper(),
                appointmentLoadService,
                crmSnapshotService,
                staffingScheduleService,
                applicationEventPublisher,
                shopRepository,
                sourceEligibilityService,
                mock(ExtPersonReplicaRepository.class),
                Clock.fixed(Instant.parse("2025-06-01T10:00:00Z"), ZoneOffset.UTC),
                mock(WorkOrderAppointmentMappingRepository.class),
                conflictEvaluator,
                conflictRecorder);

        appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        appointment = new Appointment();
        appointment.setAppointmentId(appointmentId);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setStartAt(Instant.now(TEST_CLOCK).plus(24, ChronoUnit.HOURS));
        appointment.setEndAt(Instant.now(TEST_CLOCK).plus(25, ChronoUnit.HOURS));
        lenient().when(crmSnapshotService.getCustomerById(any(UUID.class))).thenReturn(Map.of());
        lenient().when(crmSnapshotService.getVehicleById(any(UUID.class))).thenReturn(Map.of());
        lenient()
                .when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(any(UUID.class)))
                .thenReturn(List.of());
    }

    @Test
    void rescheduleAppointment_Success() {
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(i -> i.getArgument(0));

        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        Instant newStart = Instant.now(TEST_CLOCK).plus(48, ChronoUnit.HOURS);
        Instant newEnd = Instant.now(TEST_CLOCK).plus(49, ChronoUnit.HOURS);
        request.setNewStartAt(newStart);
        request.setNewEndAt(newEnd);
        request.setReason(RescheduleReasonCode.CUSTOMER_REQUEST);

        AppointmentResponse response = appointmentsService.rescheduleAppointment(appointmentId, request);

        assertNotNull(response);
        assertEquals(appointmentId, response.getAppointmentId());
        assertEquals(
                newStart.truncatedTo(ChronoUnit.MILLIS), response.getStartAt().truncatedTo(ChronoUnit.MILLIS));
        verify(appointmentRepository, times(1)).save(any(Appointment.class));
        verify(appointmentAuditRepository, times(1)).save(any());
    }

    @Test
    void rescheduleAppointment_Failure_InvalidRequest() {
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(null);
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.rescheduleAppointment(appointmentId, request);
        });

        request.setNewStartAt(Instant.now(TEST_CLOCK));
        request.setNewEndAt(Instant.now(TEST_CLOCK).minus(1, ChronoUnit.HOURS));
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.rescheduleAppointment(appointmentId, request);
        });
    }

    @Test
    void rescheduleAppointment_Failure_WrongStatus() {
        appointment.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(Instant.now(TEST_CLOCK));
        request.setNewEndAt(Instant.now(TEST_CLOCK).plus(1, ChronoUnit.HOURS));

        assertThrows(AppointmentStateException.class, () -> {
            appointmentsService.rescheduleAppointment(appointmentId, request);
        });
    }

    @Test
    void cancelAppointment_Success() {
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(i -> i.getArgument(0));

        CancelAppointmentRequest request = new CancelAppointmentRequest();
        request.setCancellationReason(CancellationReasonCode.CUSTOMER_REQUEST);

        AppointmentResponse response = appointmentsService.cancelAppointment(appointmentId, request);

        assertNotNull(response);
        assertEquals(appointmentId, response.getAppointmentId());
        assertEquals(AppointmentStatus.CANCELLED.name(), response.getStatus());
        assertEquals(CancellationReasonCode.CUSTOMER_REQUEST.name(), response.getCancellationReason());
        verify(appointmentRepository, times(1)).save(any(Appointment.class));
        verify(appointmentAuditRepository, times(1)).save(any());
    }

    @Test
    void cancelAppointment_Failure_WrongStatus() {
        appointment.setStatus(AppointmentStatus.COMPLETED);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        CancelAppointmentRequest request = new CancelAppointmentRequest();
        request.setCancellationReason(CancellationReasonCode.CUSTOMER_REQUEST);

        assertThrows(AppointmentStateException.class, () -> {
            appointmentsService.cancelAppointment(appointmentId, request);
        });
    }

    @Test
    void createAppointment_Failure_NoServiceRequests() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setServiceRequestIds(null);
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.createAppointment(request, null, null);
        });

        request.setServiceRequestIds(List.of());
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.createAppointment(request, null, null);
        });
    }

    @Test
    void createAppointment_Failure_InvalidCrmIdentifiers() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setServiceRequestIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        request.setCrmCustomerId(null);
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.createAppointment(request, null, null);
        });

        request.setCrmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmVehicleId(null);
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.createAppointment(request, null, null);
        });
    }

    // T1: AppointmentCreatedEvent must be published with correct IDs after
    // successful create
    @Test
    void createAppointment_publishesAppointmentCreatedEvent() {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID serviceRequestId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID savedId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(vehicleId);
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-06-10T14:00:00Z"));
        request.setEndAt(Instant.parse("2025-06-10T15:00:00Z"));
        request.setServiceRequestIds(List.of(serviceRequestId));

        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
            Appointment apt = inv.getArgument(0);
            apt.setAppointmentId(savedId);
            return apt;
        });

        appointmentsService.createAppointment(request, null, null);

        ArgumentCaptor<AppointmentCreatedEvent> captor = ArgumentCaptor.forClass(AppointmentCreatedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        AppointmentCreatedEvent event = captor.getValue();
        assertEquals(savedId, event.appointmentId());
        assertEquals(customerId.toString(), event.crmCustomerId());
        assertEquals(vehicleId.toString(), event.crmVehicleId());
        // PRCR-102: workorderLinkRef must be null when not set in request
        assertThat(event.workorderLinkRef()).isNull();
    }

    // T4: workorderLinkRef from the request must be persisted on the Appointment
    // entity
    @Test
    void createAppointment_persistsWorkorderLinkRef_whenSetInRequest() {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID savedId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(vehicleId);
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-06-10T14:00:00Z"));
        request.setEndAt(Instant.parse("2025-06-10T15:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        request.setWorkorderLinkRef("WO-456");

        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
            Appointment apt = inv.getArgument(0);
            apt.setAppointmentId(savedId);
            return apt;
        });

        appointmentsService.createAppointment(request, null, null);

        ArgumentCaptor<Appointment> entityCaptor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(entityCaptor.capture());
        assertEquals("WO-456", entityCaptor.getValue().getWorkorderLinkRef());
    }


    // Coverage gap: createAppointment_Failure_NoServiceRequests (above) never
    // actually reaches the serviceRequestIds==null branch — its bare request has
    // no startAt/endAt, so validateTimeRange throws first and the test's
    // assertThrows still passes for the wrong reason. This test supplies valid
    // startAt/endAt/CRM ids so the null check itself is the one that fires.
    @Test
    void createAppointment_throwsValidation_whenServiceRequestIdsNull_withOtherwiseValidRequest() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-07-01T09:00:00Z"));
        request.setEndAt(Instant.parse("2025-07-01T10:00:00Z"));
        request.setServiceRequestIds(null);

        assertThatThrownBy(() -> appointmentsService.createAppointment(request, null, null))
                .isInstanceOf(AppointmentValidationException.class)
                .hasMessageContaining("serviceRequestIds must contain at least one entry");

        verify(appointmentRepository, never()).save(any());
    }

    // PRCR-101 follow-up: a fresh Idempotency-Key (repository lookup finds
    // nothing) must fall through to the normal create path rather than short
    // circuiting — the existing-appointment branch is only for a REPEATED key.
    @Test
    void createAppointment_createsNewAppointment_whenIdempotencyKeyIsFreshAndUnseen() {
        UUID savedId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-07-01T09:00:00Z"));
        request.setEndAt(Instant.parse("2025-07-01T10:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));

        when(appointmentRepository.findByIdempotencyKey("fresh-key")).thenReturn(Optional.empty());
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
            Appointment apt = inv.getArgument(0);
            apt.setAppointmentId(savedId);
            return apt;
        });

        AppointmentResponse response = appointmentsService.createAppointment(request, "fresh-key", null).appointment();

        assertEquals(savedId, response.getAppointmentId());
        verify(appointmentRepository).save(any(Appointment.class));
    }







    // PRCR-102b: workorderLinkRef set in request must appear in
    // AppointmentCreatedEvent
    @Test
    void createAppointment_includesWorkorderLinkRefInCreatedEvent() {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID savedId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(vehicleId);
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-06-10T14:00:00Z"));
        request.setEndAt(Instant.parse("2025-06-10T15:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        request.setWorkorderLinkRef("WO-789");

        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
            Appointment apt = inv.getArgument(0);
            apt.setAppointmentId(savedId);
            return apt;
        });

        appointmentsService.createAppointment(request, null, null);

        ArgumentCaptor<AppointmentCreatedEvent> captor = ArgumentCaptor.forClass(AppointmentCreatedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().workorderLinkRef()).isEqualTo("WO-789");
    }

    // PRCR-104: CRM customer 404 must surface as CrmCustomerNotFoundException
    @Test
    void createAppointment_throwsCrmCustomerNotFound_when404() {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(crmSnapshotService.getCustomerById(any())).thenThrow(new CrmCustomerNotFoundException(customerId));

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-07-01T09:00:00Z"));
        request.setEndAt(Instant.parse("2025-07-01T10:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));

        assertThatThrownBy(() -> appointmentsService.createAppointment(request, null, null))
                .isInstanceOf(CrmCustomerNotFoundException.class);
    }

    // PRCR-104: CRM vehicle 404 must surface as CrmVehicleNotFoundException
    @Test
    void createAppointment_throwsCrmVehicleNotFound_when404() {
        UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // customer call succeeds; vehicle call throws 404
        when(crmSnapshotService.getVehicleById(any())).thenThrow(new CrmVehicleNotFoundException(vehicleId));

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmVehicleId(vehicleId);
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-07-01T09:00:00Z"));
        request.setEndAt(Instant.parse("2025-07-01T10:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));

        assertThatThrownBy(() -> appointmentsService.createAppointment(request, null, null))
                .isInstanceOf(CrmVehicleNotFoundException.class);
    }

    // ── CAP-326: the submit-time tier (DECISION-SHOPMGMT-002) ──────────────────────────────────

    private static AppointmentCreateRequest bookingRequest() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-07-01T09:00:00Z"));
        request.setEndAt(Instant.parse("2025-07-01T10:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        return request;
    }

    private static SchedulingConflictEvaluator.DetectedConflict detected(String code, ConflictSeverity severity) {
        ConflictRule rule = ConflictRule.builder()
                .id(UUID.nameUUIDFromBytes(code.getBytes()))
                .code(code)
                .severity(severity)
                .resourceType(ConflictResourceType.BAY)
                .messageTemplate(code)
                .build();
        return new SchedulingConflictEvaluator.DetectedConflict(rule, "BAY-01", code + " fired");
    }

    private static DataIntegrityViolationException overlapViolation() {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new java.sql.SQLException(
                        "ERROR: conflicting key value violates exclusion constraint \"appointment_resource_no_overlap\"",
                        "23P01"));
    }

    @Test
    void createAppointment_refusesWithTheEnvelope_whenAHardRuleFires_andRecordsTheRefusal() {
        var hard = detected("BAY_DOUBLE_BOOKED", ConflictSeverity.HARD);
        var soft = detected("FACILITY_NEAR_CAPACITY", ConflictSeverity.SOFT);
        when(conflictEvaluator.evaluate(any())).thenReturn(List.of(hard, soft));
        AppointmentCreateRequest request = bookingRequest();

        assertThatThrownBy(() -> appointmentsService.createAppointment(request, null, null))
                .isInstanceOfSatisfying(SchedulingConflictException.class, exception -> {
                    var envelope = exception.getConflictResponse();
                    assertThat(envelope.getErrorCode()).isEqualTo("SCHEDULING_CONFLICT");
                    // Every rule that fired is reported, HARD and SOFT, with the rule code verbatim.
                    assertThat(envelope.getConflicts())
                            .extracting(c -> c.getCode())
                            .containsExactly("BAY_DOUBLE_BOOKED", "FACILITY_NEAR_CAPACITY");
                    assertThat(envelope.getConflicts().get(0).getOverridable()).isFalse();
                    assertThat(envelope.getConflicts().get(1).getOverridable()).isTrue();
                });
        verify(conflictRecorder).recordRefused(any(), eq(List.of(hard, soft)));
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void createAppointment_booksAndRecordsSoftConflicts_whenOnlySoftRulesFire() {
        var soft = detected("FACILITY_NEAR_CAPACITY", ConflictSeverity.SOFT);
        when(conflictEvaluator.evaluate(any())).thenReturn(List.of(soft));
        UUID savedId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
            Appointment apt = inv.getArgument(0);
            apt.setAppointmentId(savedId);
            return apt;
        });

        AppointmentCreation creation = appointmentsService.createAppointment(bookingRequest(), null, null);

        assertThat(creation.replayed()).isFalse();
        assertEquals(savedId, creation.appointment().getAppointmentId());
        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(conflictRecorder).recordAccepted(captor.capture(), eq(List.of(soft)));
        assertEquals(savedId, captor.getValue().getAppointmentId());
        verify(conflictRecorder, never()).recordRefused(any(), any());
    }

    @Test
    void createAppointment_replaysTheExistingAppointment_whenTheRequestIsAKeylessExactDuplicate() {
        Appointment existing = Appointment.builder()
                .appointmentId(UUID.fromString("00000000-0000-0000-0000-000000000099"))
                .status(AppointmentStatus.SCHEDULED)
                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .resourceId("BAY-01")
                .crmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .crmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .startAt(Instant.parse("2025-07-01T09:00:00Z"))
                .endAt(Instant.parse("2025-07-01T10:00:00Z"))
                .build();
        when(conflictRecorder.findKeylessDuplicate(any())).thenReturn(Optional.of(existing));

        AppointmentCreation creation = appointmentsService.createAppointment(bookingRequest(), null, null);

        // Not a new row, not a 409, not a BAY_DOUBLE_BOOKED against the caller's own booking (D17 item 3).
        assertThat(creation.replayed()).isTrue();
        assertEquals(existing.getAppointmentId(), creation.appointment().getAppointmentId());
        verify(appointmentRepository, never()).save(any());
        verify(conflictEvaluator, never()).evaluate(any());
    }

    @Test
    void createAppointment_recordsBayDoubleBooked_whenTheExclusionConstraintRefusesTheInsert() {
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(overlapViolation()).when(appointmentRepository).flush();
        var overlap = detected("BAY_DOUBLE_BOOKED", ConflictSeverity.HARD);
        when(conflictRecorder.recordRefusedOverlap(any())).thenReturn(overlap);

        assertThatThrownBy(() -> appointmentsService.createAppointment(bookingRequest(), null, null))
                .isInstanceOfSatisfying(SchedulingConflictException.class, exception -> assertThat(
                                exception.getConflictResponse().getConflicts())
                        .singleElement()
                        .extracting(c -> c.getCode())
                        .isEqualTo("BAY_DOUBLE_BOOKED"));
        // The race check came first, on the recorder's own connection.
        verify(conflictRecorder, times(2)).findKeylessDuplicate(any());
    }

    @Test
    void createAppointment_replaysTheTwin_whenTheExclusionConstraintRefusesAnExactKeylessDoubleSubmit() {
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(overlapViolation()).when(appointmentRepository).flush();
        Appointment twin = Appointment.builder()
                .appointmentId(UUID.fromString("00000000-0000-0000-0000-000000000077"))
                .build();
        when(conflictRecorder.findKeylessDuplicate(any())).thenReturn(Optional.empty(), Optional.of(twin));

        assertThatThrownBy(() -> appointmentsService.createAppointment(bookingRequest(), null, null))
                .isInstanceOfSatisfying(KeylessDuplicateReplayException.class, raced ->
                        assertEquals(twin.getAppointmentId(), raced.getExistingAppointmentId()));
        verify(conflictRecorder, never()).recordRefusedOverlap(any());
    }

    @Test
    void createAppointment_rethrows_whenTheIntegrityViolationIsNotTheOverlapConstraint() {
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataIntegrityViolationException("not null", new java.sql.SQLException("null value", "23502")))
                .when(appointmentRepository)
                .flush();

        assertThatThrownBy(() -> appointmentsService.createAppointment(bookingRequest(), null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(conflictRecorder, never()).recordRefusedOverlap(any());
    }
}
