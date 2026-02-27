package com.positivity.shopmanager.internal.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.events.EventEmissionService;
import com.positivity.shopmanager.internal.client.CrmCustomerClient;
import com.positivity.shopmanager.internal.client.CrmVehicleClient;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.CancelAppointmentRequest;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.CancellationReasonCode;
import com.positivity.shopmanager.internal.exception.AppointmentStateException;
import com.positivity.shopmanager.internal.exception.AppointmentValidationException;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.adapter.CustomerAdapter;
import com.positivity.shopmanager.internal.adapter.VehicleAdapter;
import com.positivity.shopmanager.internal.adapter.WorkorderAdapter;
import com.positivity.shopmanager.service.AppointmentLoadService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class AppointmentsServiceNewBehaviorsTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private AppointmentAuditRepository appointmentAuditRepository;
    @Mock
    private AppointmentServiceRequestRepository appointmentServiceRequestRepository;
    @Mock
    private AppointmentLoadService appointmentLoadService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private CrmCustomerClient crmCustomerClient;
    @Mock
    private CrmVehicleClient crmVehicleClient;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private EventEmissionService eventEmissionService;
    @Mock
    private CustomerAdapter customerAdapter;
    @Mock
    private VehicleAdapter vehicleAdapter;
    @Mock
    private WorkorderAdapter workorderAdapter;

    @InjectMocks
    private AppointmentsServiceImpl appointmentsService;

    private UUID appointmentId;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        appointmentId = UUID.randomUUID();
        appointment = new Appointment();
        appointment.setAppointmentId(appointmentId);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setStartAt(Instant.now().plus(24, ChronoUnit.HOURS));
        appointment.setEndAt(Instant.now().plus(25, ChronoUnit.HOURS));
        lenient().when(crmCustomerClient.getCustomerById(any(UUID.class))).thenReturn(Map.of());
        lenient().when(crmVehicleClient.getVehicleById(any(UUID.class))).thenReturn(Map.of());
        lenient().when(appointmentServiceRequestRepository.findByAppointmentId(any(UUID.class))).thenReturn(List.of());
    }

    @Test
    void rescheduleAppointment_Success() {
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(i -> i.getArgument(0));

        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        Instant newStart = Instant.now().plus(48, ChronoUnit.HOURS);
        Instant newEnd = Instant.now().plus(49, ChronoUnit.HOURS);
        request.setNewStartAt(newStart);
        request.setNewEndAt(newEnd);

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

        request.setNewStartAt(Instant.now());
        request.setNewEndAt(Instant.now().minus(1, ChronoUnit.HOURS));
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.rescheduleAppointment(appointmentId, request);
        });
    }

    @Test
    void rescheduleAppointment_Failure_WrongStatus() {
        appointment.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(Instant.now());
        request.setNewEndAt(Instant.now().plus(1, ChronoUnit.HOURS));

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
            appointmentsService.createAppointment(request);
        });

        request.setServiceRequestIds(List.of());
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.createAppointment(request);
        });
    }

    @Test
    void createAppointment_Failure_InvalidCrmIdentifiers() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setServiceRequestIds(List.of(UUID.randomUUID()));
        request.setCrmCustomerId(null);
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.createAppointment(request);
        });

        request.setCrmCustomerId(UUID.randomUUID());
        request.setCrmVehicleId(null);
        assertThrows(AppointmentValidationException.class, () -> {
            appointmentsService.createAppointment(request);
        });
    }
}