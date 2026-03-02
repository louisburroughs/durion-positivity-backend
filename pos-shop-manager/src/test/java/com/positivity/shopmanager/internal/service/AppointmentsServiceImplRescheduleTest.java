package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.client.CrmCustomerClient;
import com.positivity.shopmanager.internal.client.CrmVehicleClient;
import com.positivity.shopmanager.internal.client.HrAvailabilityClient;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.RescheduleHistory;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.exception.AppointmentStateException;
import com.positivity.shopmanager.internal.exception.AppointmentValidationException;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.RescheduleHistoryRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.service.AppointmentLoadService;
import com.positivity.shopmanager.service.SourceEligibilityService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class AppointmentsServiceImplRescheduleTest {

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
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ShopRepository shopRepository;
    @Mock
    private SourceEligibilityService sourceEligibilityService;

    private AppointmentsServiceImpl appointmentsService;

    private static final Instant FIXED_NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final UUID APPOINTMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
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
                eventPublisher,
                shopRepository,
                sourceEligibilityService,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void rescheduleAppointment_withNullStartAndEndTimes_throwsValidationException() {
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request))
                .isInstanceOf(AppointmentValidationException.class)
                .hasMessageContaining("newStartAt and newEndAt are required");
    }

    @Test
    void rescheduleAppointment_withStartAfterEndTime_throwsValidationException() {
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(Instant.now().plusSeconds(3600));
        request.setNewEndAt(Instant.now());
        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request))
                .isInstanceOf(AppointmentValidationException.class)
                .hasMessageContaining("newStartAt must be before newEndAt");
    }

    @Test
    void rescheduleAppointment_withInvalidStatus_throwsStateException() {
        Appointment appointment = new Appointment();
        appointment.setStatus(AppointmentStatus.COMPLETED);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));

        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(Instant.now());
        request.setNewEndAt(Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request))
                .isInstanceOf(AppointmentStateException.class)
                .hasMessageContaining("must be SCHEDULED, CONFIRMED, or AWAITING_PARTS");
    }

    @Test
    void rescheduleAppointment_withOtherReasonAndNoNotes_throwsValidationException() {
        Appointment appointment = new Appointment();
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));

        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(Instant.now());
        request.setNewEndAt(Instant.now().plusSeconds(3600));
        request.setReason(RescheduleReasonCode.OTHER);
        request.setRescheduleReasonNotes(" ");

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request))
                .isInstanceOf(AppointmentValidationException.class)
                .hasMessageContaining("rescheduleReasonNotes is required when reason is OTHER");
    }

    @Test
    void rescheduleAppointment_withNullReason_throwsValidationException() {
        Appointment appointment = new Appointment();
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));

        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(Instant.now());
        request.setNewEndAt(Instant.now().plusSeconds(3600));
        request.setReason(null);

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request))
                .isInstanceOf(AppointmentValidationException.class)
                .hasMessageContaining("reason is required for rescheduling");
    }

    @Test
    void rescheduleAppointment_withNonExistentAppointment_throwsNotFoundException() {
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.empty());
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(Instant.now());
        request.setNewEndAt(Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request))
                .isInstanceOf(AppointmentNotFoundException.class);
    }

    @Test
    void rescheduleAppointment_success() {
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(APPOINTMENT_ID);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setStartAt(Instant.parse("2026-03-01T10:00:00Z"));
        appointment.setEndAt(Instant.parse("2026-03-01T11:00:00Z"));
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));

        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        Instant newStart = Instant.parse("2026-03-02T14:00:00Z");
        Instant newEnd = Instant.parse("2026-03-02T15:00:00Z");
        request.setNewStartAt(newStart);
        request.setNewEndAt(newEnd);
        request.setReason(RescheduleReasonCode.CUSTOMER_REQUEST);

        appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request);

        ArgumentCaptor<Appointment> appointmentCaptor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(appointmentCaptor.capture());
        assertThat(appointmentCaptor.getValue().getStartAt()).isEqualTo(newStart);
        assertThat(appointmentCaptor.getValue().getEndAt()).isEqualTo(newEnd);

        ArgumentCaptor<RescheduleHistory> historyCaptor = ArgumentCaptor.forClass(RescheduleHistory.class);
        verify(rescheduleHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getAppointmentId()).isEqualTo(APPOINTMENT_ID);
        assertThat(historyCaptor.getValue().getNewStartAt()).isEqualTo(newStart);
        assertThat(historyCaptor.getValue().getRescheduleReason()).isEqualTo(RescheduleReasonCode.CUSTOMER_REQUEST);

        verify(eventPublisher, never()).publishEvent(any()); // No workorderLinkRef, so no event
    }
}
