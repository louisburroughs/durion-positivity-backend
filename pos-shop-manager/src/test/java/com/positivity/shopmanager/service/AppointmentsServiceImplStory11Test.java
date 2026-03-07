package com.positivity.shopmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.client.CrmCustomerClient;
import com.positivity.shopmanager.internal.client.CrmVehicleClient;
import com.positivity.shopmanager.internal.client.HrAvailabilityClient;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.RescheduleHistory;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.shopmanager.internal.event.AppointmentRescheduledEvent;
import com.positivity.shopmanager.internal.exception.AppointmentStateException;
import com.positivity.shopmanager.internal.exception.AppointmentValidationException;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.RescheduleHistoryRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.internal.service.AppointmentsServiceImpl;

/**
 * Unit tests for CAP-249 Story #11: Reschedule Appointment with Notifications.
 *
 * <p>
 * Verifies that {@link AppointmentsServiceImpl#rescheduleAppointment} enforces:
 * <ul>
 * <li>Expanded eligibility: SCHEDULED, CHECKED_IN, and WAITING_FOR_PARTS are
 * all
 * accepted.</li>
 * <li>Ineligible status rejection: CANCELLED and WORK_IN_PROGRESS throw
 * {@link AppointmentStateException}.</li>
 * <li>Mandatory reason: {@code reason=OTHER} requires non-blank
 * {@code rescheduleReasonNotes}.</li>
 * <li>Reschedule history is persisted with all required fields.</li>
 * <li>{@link AppointmentRescheduledEvent} is emitted with
 * {@code rescheduleReason} and {@code rescheduledAt}.</li>
 * </ul>
 *
 * Issue: #11
 */
@ExtendWith(MockitoExtension.class)
class AppointmentsServiceImplStory11Test {

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
    private static final Instant ORIGINAL_START = Instant.parse("2026-03-10T10:00:00Z");
    private static final Instant ORIGINAL_END = Instant.parse("2026-03-10T11:00:00Z");
    private static final Instant NEW_START = Instant.parse("2026-03-11T10:00:00Z");
    private static final Instant NEW_END = Instant.parse("2026-03-11T11:00:00Z");

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

    // ─── AC: Expanded eligibility — allowed statuses ──────────────────────────

    /**
     * AC: SCHEDULED appointments must be reschedulable.
     * Verifies appointment is saved and reschedule history is persisted.
     */
    @Test
    void reschedule_withScheduledStatus_succeeds() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        RescheduleAppointmentRequest request = buildRequest(RescheduleReasonCode.CUSTOMER_REQUEST, null, false);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AppointmentResponse response = appointmentsService.rescheduleAppointment(appointmentId, request);

        assertThat(response.getStartAt()).isEqualTo(NEW_START);
        assertThat(response.getEndAt()).isEqualTo(NEW_END);
        verify(appointmentRepository).save(appointment);
        verify(rescheduleHistoryRepository).save(any(RescheduleHistory.class));
    }

    /**
     * AC: CHECKED_IN appointments must also be eligible for reschedule (expanded
     * eligibility).
     */
    @Test
    void reschedule_withConfirmedStatus_succeeds() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.CHECKED_IN);
        RescheduleAppointmentRequest request = buildRequest(RescheduleReasonCode.SHOP_CAPACITY, null, false);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AppointmentResponse response = appointmentsService.rescheduleAppointment(appointmentId, request);

        assertThat(response.getStartAt()).isEqualTo(NEW_START);
        verify(rescheduleHistoryRepository).save(any(RescheduleHistory.class));
    }

    /**
     * AC: WAITING_FOR_PARTS appointments must also be eligible for reschedule
     * (expanded eligibility).
     */
    @Test
    void reschedule_withAwaitingPartsStatus_succeeds() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.WAITING_FOR_PARTS);
        RescheduleAppointmentRequest request = buildRequest(RescheduleReasonCode.PARTS_DELAY, null, false);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AppointmentResponse response = appointmentsService.rescheduleAppointment(appointmentId, request);

        assertThat(response.getStartAt()).isEqualTo(NEW_START);
        verify(rescheduleHistoryRepository).save(any(RescheduleHistory.class));
    }

    // ─── AC: Expanded eligibility — rejected statuses ─────────────────────────

    /**
     * AC: CANCELLED appointments must be rejected with AppointmentStateException.
     */
    @Test
    void reschedule_withCancelledStatus_throwsAppointmentStateException() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.CANCELLED);
        RescheduleAppointmentRequest request = buildRequest(RescheduleReasonCode.CUSTOMER_REQUEST, null, false);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(appointmentId, request))
                .isInstanceOf(AppointmentStateException.class)
                .hasMessageContaining("CANCELLED");
    }

    /**
     * AC: WORK_IN_PROGRESS appointments must also be rejected (not in the eligible
     * set).
     */
    @Test
    void reschedule_withInProgressStatus_throwsAppointmentStateException() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.WORK_IN_PROGRESS);
        RescheduleAppointmentRequest request = buildRequest(RescheduleReasonCode.CUSTOMER_REQUEST, null, false);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(appointmentId, request))
                .isInstanceOf(AppointmentStateException.class)
                .hasMessageContaining("WORK_IN_PROGRESS");
    }

    // ─── AC: reason=OTHER requires non-blank notes ────────────────────────────

    /**
     * AC: reason=OTHER with null notes must throw AppointmentValidationException.
     */
    @Test
    void reschedule_withOtherReasonAndNullNotes_throwsAppointmentValidationException() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        RescheduleAppointmentRequest request = buildRequest(RescheduleReasonCode.OTHER, null, false);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(appointmentId, request))
                .isInstanceOf(AppointmentValidationException.class)
                .hasMessageContaining("rescheduleReasonNotes");
    }

    /**
     * AC: reason=OTHER with blank notes must throw AppointmentValidationException.
     */
    @Test
    void reschedule_withOtherReasonAndBlankNotes_throwsAppointmentValidationException() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        RescheduleAppointmentRequest request = buildRequest(RescheduleReasonCode.OTHER, "   ", false);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(appointmentId, request))
                .isInstanceOf(AppointmentValidationException.class)
                .hasMessageContaining("rescheduleReasonNotes");
    }

    /**
     * AC: reason=OTHER with a non-blank note must succeed without error.
     */
    @Test
    void reschedule_withOtherReasonAndValidNotes_succeeds() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        RescheduleAppointmentRequest request = buildRequest(
                RescheduleReasonCode.OTHER, "Customer rescheduled due to work conflict", false);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AppointmentResponse response = appointmentsService.rescheduleAppointment(appointmentId, request);

        assertThat(response.getStartAt()).isEqualTo(NEW_START);
        verify(rescheduleHistoryRepository).save(any(RescheduleHistory.class));
    }

    // ─── AC: Reschedule history saved with correct fields ─────────────────────

    /**
     * AC: RescheduleHistory must be persisted with reason, rescheduledBy,
     * notifyCustomer,
     * previousStartAt, newStartAt, newEndAt, and rescheduledAt matching the clock.
     */
    @Test
    void reschedule_savesRescheduleHistory_withCorrectFields() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        RescheduleAppointmentRequest request = buildRequest(RescheduleReasonCode.WEATHER, "Storm warning", true);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        appointmentsService.rescheduleAppointment(appointmentId, request);

        // Issue #11: verify all required fields are persisted in reschedule history
        ArgumentCaptor<RescheduleHistory> historyCaptor = ArgumentCaptor.forClass(RescheduleHistory.class);
        verify(rescheduleHistoryRepository).save(historyCaptor.capture());
        RescheduleHistory saved = historyCaptor.getValue();

        assertThat(saved.getAppointmentId()).isEqualTo(appointmentId);
        assertThat(saved.getRescheduleReason()).isEqualTo(RescheduleReasonCode.WEATHER);
        assertThat(saved.getRescheduleReasonNotes()).isEqualTo("Storm warning");
        assertThat(saved.isNotifyCustomer()).isTrue();
        assertThat(saved.getPreviousStartAt()).isEqualTo(ORIGINAL_START);
        assertThat(saved.getPreviousEndAt()).isEqualTo(ORIGINAL_END);
        assertThat(saved.getNewStartAt()).isEqualTo(NEW_START);
        assertThat(saved.getNewEndAt()).isEqualTo(NEW_END);
        assertThat(saved.getRescheduledAt()).isEqualTo(FIXED_NOW);
        assertThat(saved.getRescheduledBy()).isNotBlank();
    }

    // ─── AC: Event emitted with reason and rescheduledAt ─────────────────────

    /**
     * AC: AppointmentRescheduledEvent must include rescheduleReason and a non-null
     * rescheduledAt.
     * The workorderLinkRef must be set on the appointment so the event is
     * published.
     */
    @Test
    void reschedule_emitsEvent_withReasonAndRescheduledAt() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        appointment.setWorkorderLinkRef("wo-ref-001"); // event is conditional on non-null workorderLinkRef

        RescheduleAppointmentRequest request = buildRequest(RescheduleReasonCode.MECHANIC_UNAVAILABLE, null, false);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        appointmentsService.rescheduleAppointment(appointmentId, request);

        // Issue #11: event must carry reason and clock-anchored rescheduledAt
        ArgumentCaptor<AppointmentRescheduledEvent> eventCaptor = ArgumentCaptor
                .forClass(AppointmentRescheduledEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AppointmentRescheduledEvent event = eventCaptor.getValue();

        assertThat(event.appointmentId()).isEqualTo(appointmentId);
        assertThat(event.workorderLinkRef()).isEqualTo("wo-ref-001");
        assertThat(event.rescheduleReason()).isEqualTo(RescheduleReasonCode.MECHANIC_UNAVAILABLE);
        assertThat(event.rescheduledAt()).isEqualTo(FIXED_NOW);
        assertThat(event.newStartAt()).isEqualTo(NEW_START);
        assertThat(event.newEndAt()).isEqualTo(NEW_END);
        assertThat(event.previousStartAt()).isEqualTo(ORIGINAL_START);
        assertThat(event.previousEndAt()).isEqualTo(ORIGINAL_END);
    }

    /**
     * Conditional event emission: appointments without a workorderLinkRef should
     * NOT emit an AppointmentRescheduledEvent (no downstream workexec consumer).
     */
    @Test
    void reschedule_doesNotEmitEvent_whenWorkorderLinkRefIsNull() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        // workorderLinkRef left null (default from buildAppointment)

        RescheduleAppointmentRequest request = buildRequest(RescheduleReasonCode.CUSTOMER_REQUEST, null, true);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        appointmentsService.rescheduleAppointment(appointmentId, request);

        verify(eventPublisher, never()).publishEvent(any());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Appointment buildAppointment(UUID appointmentId, AppointmentStatus status) {
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(appointmentId);
        appointment.setStatus(status);
        appointment.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        appointment.setResourceId("tech-1");
        appointment.setCrmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        appointment.setCrmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        appointment.setStartAt(ORIGINAL_START);
        appointment.setEndAt(ORIGINAL_END);
        return appointment;
    }

    /**
     * Builds a {@link RescheduleAppointmentRequest} with fixed NEW_START / NEW_END.
     *
     * @param reason         the mandatory reschedule reason code
     * @param notes          optional free-text notes (required when reason is
     *                       OTHER)
     * @param notifyCustomer whether to notify the customer of the reschedule
     * @return a fully populated request for service-layer unit testing
     */
    private RescheduleAppointmentRequest buildRequest(
            RescheduleReasonCode reason, String notes, boolean notifyCustomer) {
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(NEW_START);
        request.setNewEndAt(NEW_END);
        request.setReason(reason);
        request.setRescheduleReasonNotes(notes);
        request.setNotifyCustomer(notifyCustomer);
        return request;
    }
}
