package com.positivity.shopmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.client.CrmCustomerClient;
import com.positivity.shopmanager.internal.client.CrmVehicleClient;
import com.positivity.shopmanager.internal.client.HrAvailabilityClient;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
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
import com.positivity.shopmanager.internal.repository.RescheduleHistoryRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.internal.service.AppointmentsServiceImpl;

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
    private CrmCustomerClient crmCustomerClient;
    @Mock
    private CrmVehicleClient crmVehicleClient;
    @Mock
    private HrAvailabilityClient hrAvailabilityClient;
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
                crmCustomerClient,
                crmVehicleClient,
                hrAvailabilityClient,
                applicationEventPublisher,
                shopRepository,
                sourceEligibilityService,
                Clock.fixed(Instant.parse("2025-06-01T10:00:00Z"), ZoneOffset.UTC));

        appointmentId = UUID.randomUUID();
        appointment = new Appointment();
        appointment.setAppointmentId(appointmentId);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setStartAt(Instant.now(TEST_CLOCK).plus(24, ChronoUnit.HOURS));
        appointment.setEndAt(Instant.now(TEST_CLOCK).plus(25, ChronoUnit.HOURS));
        lenient().when(crmCustomerClient.getCustomerById(any(UUID.class))).thenReturn(Map.of());
        lenient().when(crmVehicleClient.getVehicleById(any(UUID.class))).thenReturn(Map.of());
        lenient().when(appointmentServiceRequestRepository.findByAppointmentId(any(UUID.class))).thenReturn(List.of());
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
        assertEquals(newStart.truncatedTo(ChronoUnit.MILLIS), response.getStartAt().truncatedTo(ChronoUnit.MILLIS));
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
        request.setServiceRequestIds(List.of(UUID.randomUUID()));
        request.setCrmCustomerId(null);
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.createAppointment(request, null, null);
        });

        request.setCrmCustomerId(UUID.randomUUID());
        request.setCrmVehicleId(null);
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.createAppointment(request, null, null);
        });
    }

    // T1: AppointmentCreatedEvent must be published with correct IDs after
    // successful create
    @Test
    void createAppointment_publishesAppointmentCreatedEvent() {
        UUID customerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID serviceRequestId = UUID.randomUUID();
        UUID savedId = UUID.randomUUID();

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.randomUUID());
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
        UUID customerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID savedId = UUID.randomUUID();

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.randomUUID());
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(vehicleId);
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-06-10T14:00:00Z"));
        request.setEndAt(Instant.parse("2025-06-10T15:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.randomUUID()));
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

    // PRCR-101: slot conflict detection — throws AppointmentValidationException
    // when
    // the repository returns an overlapping SCHEDULED appointment for the same
    // resource
    @Test
    void createAppointment_throwsConflict_whenSlotOverlaps() {
        UUID customerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();

        var conflictingAppointment = mock(Appointment.class);
        when(conflictingAppointment.getResourceId()).thenReturn("BAY-01");
        when(conflictingAppointment.getStatus()).thenReturn(AppointmentStatus.SCHEDULED);
        // Different customer so the de-duplicate filter does NOT exclude this record
        when(conflictingAppointment.getCrmCustomerId()).thenReturn(UUID.randomUUID());
        when(appointmentRepository.findByLocationIdAndStartAtLessThanAndEndAtGreaterThan(
                any(), any(), any())).thenReturn(List.of(conflictingAppointment));

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.randomUUID());
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(vehicleId);
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-07-01T09:00:00Z"));
        request.setEndAt(Instant.parse("2025-07-01T10:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> appointmentsService.createAppointment(request, null, null))
                .isInstanceOf(AppointmentValidationException.class)
                .hasMessageContaining("slot");
    }

    // PRCR-102b: workorderLinkRef set in request must appear in
    // AppointmentCreatedEvent
    @Test
    void createAppointment_includesWorkorderLinkRefInCreatedEvent() {
        UUID customerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID savedId = UUID.randomUUID();

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.randomUUID());
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(vehicleId);
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-06-10T14:00:00Z"));
        request.setEndAt(Instant.parse("2025-06-10T15:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.randomUUID()));
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
        UUID customerId = UUID.randomUUID();
        when(crmCustomerClient.getCustomerById(any()))
                .thenThrow(new CrmCustomerNotFoundException(customerId));

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.randomUUID());
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(UUID.randomUUID());
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-07-01T09:00:00Z"));
        request.setEndAt(Instant.parse("2025-07-01T10:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> appointmentsService.createAppointment(request, null, null))
                .isInstanceOf(CrmCustomerNotFoundException.class);
    }

    // PRCR-104: CRM vehicle 404 must surface as CrmVehicleNotFoundException
    @Test
    void createAppointment_throwsCrmVehicleNotFound_when404() {
        UUID vehicleId = UUID.randomUUID();
        // customer call succeeds; vehicle call throws 404
        when(crmVehicleClient.getVehicleById(any()))
                .thenThrow(new CrmVehicleNotFoundException(vehicleId));

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.randomUUID());
        request.setCrmCustomerId(UUID.randomUUID());
        request.setCrmVehicleId(vehicleId);
        request.setResourceId("BAY-01");
        request.setStartAt(Instant.parse("2025-07-01T09:00:00Z"));
        request.setEndAt(Instant.parse("2025-07-01T10:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> appointmentsService.createAppointment(request, null, null))
                .isInstanceOf(CrmVehicleNotFoundException.class);
    }
}
