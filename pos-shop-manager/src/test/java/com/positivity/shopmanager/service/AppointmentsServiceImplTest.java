package com.positivity.shopmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.client.CrmCustomerClient;
import com.positivity.shopmanager.internal.client.CrmVehicleClient;
import com.positivity.shopmanager.internal.dto.AppointmentCreateModel;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.CancelAppointmentRequest;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewResponse;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentAudit;
import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import com.positivity.shopmanager.internal.enums.AppointmentAction;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.CancellationReasonCode;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.exception.AppointmentStateException;
import com.positivity.shopmanager.internal.exception.AppointmentValidationException;
import com.positivity.shopmanager.internal.exception.LocationNotFoundException;
import com.positivity.shopmanager.internal.exception.VehicleCustomerMismatchException;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.RescheduleHistoryRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.internal.service.AppointmentsServiceImpl;
import com.positivity.shopmanager.internal.service.StaffingScheduleService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
class AppointmentsServiceImplTest {

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
                crmCustomerClient,
                crmVehicleClient,
                staffingScheduleService,
                eventPublisher,
                shopRepository,
                sourceEligibilityService,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void reschedule_succeeds_whenScheduled() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant originalStart = Instant.parse("2026-03-10T10:00:00Z");
        Instant originalEnd = Instant.parse("2026-03-10T11:00:00Z");
        Instant newStart = Instant.parse("2026-03-11T10:00:00Z");
        Instant newEnd = Instant.parse("2026-03-11T11:00:00Z");

        Appointment appointment =
                buildAppointment(appointmentId, AppointmentStatus.SCHEDULED, originalStart, originalEnd);
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(newStart);
        request.setNewEndAt(newEnd);
        request.setReason(RescheduleReasonCode.CUSTOMER_REQUEST);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(appointmentId))
                .thenReturn(List.<AppointmentServiceRequest>of());

        AppointmentResponse response = appointmentsService.rescheduleAppointment(appointmentId, request);

        assertEquals(AppointmentStatus.SCHEDULED.name(), response.getStatus());
        assertEquals(newStart, response.getStartAt());
        assertEquals(newEnd, response.getEndAt());

        verify(appointmentRepository).save(appointment);

        ArgumentCaptor<AppointmentAudit> auditCaptor = ArgumentCaptor.forClass(AppointmentAudit.class);
        verify(appointmentAuditRepository).save(auditCaptor.capture());
        AppointmentAudit audit = auditCaptor.getValue();
        assertEquals(appointmentId, audit.getAppointmentId());
        assertEquals(AppointmentAction.RESCHEDULED, audit.getAction());
        assertEquals(originalStart, audit.getPreviousStartAt());
        assertEquals(originalEnd, audit.getPreviousEndAt());
        assertEquals(newStart, audit.getNewStartAt());
        assertEquals(newEnd, audit.getNewEndAt());
    }

    @Test
    void reschedule_throwsAppointmentNotFoundException_whenNotFound() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(Instant.parse("2026-03-11T10:00:00Z"));
        request.setNewEndAt(Instant.parse("2026-03-11T11:00:00Z"));
        request.setReason(RescheduleReasonCode.CUSTOMER_REQUEST);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.empty());

        assertThrows(
                AppointmentNotFoundException.class,
                () -> appointmentsService.rescheduleAppointment(appointmentId, request));
    }

    @Test
    void reschedule_throwsAppointmentStateException_whenNotScheduled() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(
                appointmentId,
                AppointmentStatus.CANCELLED,
                Instant.parse("2026-03-10T10:00:00Z"),
                Instant.parse("2026-03-10T11:00:00Z"));
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(Instant.parse("2026-03-11T10:00:00Z"));
        request.setNewEndAt(Instant.parse("2026-03-11T11:00:00Z"));
        request.setReason(RescheduleReasonCode.CUSTOMER_REQUEST);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThrows(
                AppointmentStateException.class,
                () -> appointmentsService.rescheduleAppointment(appointmentId, request));
    }

    @Test
    void cancel_succeeds_whenScheduled() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(
                appointmentId,
                AppointmentStatus.SCHEDULED,
                Instant.parse("2026-03-10T10:00:00Z"),
                Instant.parse("2026-03-10T11:00:00Z"));

        CancelAppointmentRequest request = new CancelAppointmentRequest();
        request.setCancellationReason(CancellationReasonCode.CUSTOMER_REQUEST);
        request.setNotes("Customer requested cancellation");

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(appointmentId))
                .thenReturn(List.<AppointmentServiceRequest>of());

        AppointmentResponse response = appointmentsService.cancelAppointment(appointmentId, request);

        assertEquals(AppointmentStatus.CANCELLED.name(), response.getStatus());
        assertEquals(CancellationReasonCode.CUSTOMER_REQUEST.name(), response.getCancellationReason());
        assertEquals("Customer requested cancellation", response.getCancellationNotes());
        verify(appointmentRepository).save(appointment);

        ArgumentCaptor<AppointmentAudit> auditCaptor = ArgumentCaptor.forClass(AppointmentAudit.class);
        verify(appointmentAuditRepository).save(auditCaptor.capture());
        AppointmentAudit audit = auditCaptor.getValue();
        assertEquals(appointmentId, audit.getAppointmentId());
        assertEquals(AppointmentAction.CANCELLED, audit.getAction());
        assertEquals(CancellationReasonCode.CUSTOMER_REQUEST.name(), audit.getCancellationReason());
    }

    @Test
    void cancel_throwsAppointmentNotFoundException_whenNotFound() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CancelAppointmentRequest request = new CancelAppointmentRequest();
        request.setCancellationReason(CancellationReasonCode.OTHER);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.empty());

        assertThrows(
                AppointmentNotFoundException.class,
                () -> appointmentsService.cancelAppointment(appointmentId, request));
    }

    @Test
    void cancel_throwsAppointmentStateException_whenNotScheduled() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Appointment appointment = buildAppointment(
                appointmentId,
                AppointmentStatus.COMPLETED,
                Instant.parse("2026-03-10T10:00:00Z"),
                Instant.parse("2026-03-10T11:00:00Z"));
        CancelAppointmentRequest request = new CancelAppointmentRequest();
        request.setCancellationReason(CancellationReasonCode.OTHER);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThrows(
                AppointmentStateException.class, () -> appointmentsService.cancelAppointment(appointmentId, request));
    }

    @Test
    void getScheduleView_returnsEmptyResources_whenLocationExistsAndNoAppointments() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ScheduleViewRequest request = scheduleRequest(locationId, LocalDate.of(2026, 3, 10), false);

        when(shopRepository.findById(locationId)).thenReturn(Optional.empty());
        when(appointmentRepository.findByLocationIdAndStartAtLessThanAndEndAtGreaterThan(
                        any(UUID.class), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(shopRepository.existsById(locationId)).thenReturn(true);

        ScheduleViewResponse response =
                appointmentsService.getScheduleView(request, UUID.fromString("00000000-0000-0000-0000-000000000001"));

        assertEquals(locationId, response.getLocationId());
        assertEquals(request.getDate(), response.getDate());
        assertEquals("NOT_REQUESTED", response.getAvailabilityOverlayStatus());
        assertNotNull(response.getResources());
        assertTrue(response.getResources().isEmpty());
        assertNotNull(response.getWarnings());
        assertTrue(response.getWarnings().isEmpty());
    }

    @Test
    void getScheduleView_throwsLocationNotFoundException_whenNoAppointmentsAndLocationUnknown() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ScheduleViewRequest request = scheduleRequest(locationId, LocalDate.of(2026, 3, 10), false);

        when(shopRepository.findById(locationId)).thenReturn(Optional.empty());
        when(appointmentRepository.findByLocationIdAndStartAtLessThanAndEndAtGreaterThan(
                        any(UUID.class), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(shopRepository.existsById(locationId)).thenReturn(false);

        assertThrows(
                LocationNotFoundException.class,
                () -> appointmentsService.getScheduleView(
                        request, UUID.fromString("00000000-0000-0000-0000-000000000001")));
    }

    @Test
    void getScheduleView_marksConflicts_whenTwoAppointmentsOverlapForSameResource() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ScheduleViewRequest request = scheduleRequest(locationId, LocalDate.of(2026, 3, 10), false);

        Appointment first = buildAppointment(
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                AppointmentStatus.SCHEDULED,
                Instant.parse("2026-03-10T10:00:00Z"),
                Instant.parse("2026-03-10T11:00:00Z"));
        first.setLocationId(locationId);
        first.setResourceId("tech-1");
        first.setResourceType("TECHNICIAN");

        Appointment second = buildAppointment(
                UUID.fromString("00000000-0000-0000-0000-000000000022"),
                AppointmentStatus.SCHEDULED,
                Instant.parse("2026-03-10T10:30:00Z"),
                Instant.parse("2026-03-10T11:30:00Z"));
        second.setLocationId(locationId);
        second.setResourceId("tech-1");
        second.setResourceType("TECHNICIAN");

        when(shopRepository.findById(locationId)).thenReturn(Optional.empty());
        when(appointmentRepository.findByLocationIdAndStartAtLessThanAndEndAtGreaterThan(
                        any(UUID.class), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(first, second));

        ScheduleViewResponse response =
                appointmentsService.getScheduleView(request, UUID.fromString("00000000-0000-0000-0000-000000000001"));

        assertEquals(1, response.getResources().size());
        var events = response.getResources().get(0).getEvents();
        assertEquals(2, events.size());
        assertTrue(events.get(0).isHasConflict());
        assertTrue(events.get(1).isHasConflict());
        assertEquals("BLOCKING", events.get(0).getSeverity());
        assertEquals("BLOCKING", events.get(1).getSeverity());
        assertEquals(
                List.of("APT-00000000-0000-0000-0000-000000000022"),
                events.get(0).getConflictDetails().getConflictingEventIds());
        assertEquals(
                List.of("APT-00000000-0000-0000-0000-000000000011"),
                events.get(1).getConflictDetails().getConflictingEventIds());
    }

    @Test
    void getScheduleView_setsUnavailableOverlay_whenReplicaHasNoStaffingData() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ScheduleViewRequest request = scheduleRequest(locationId, LocalDate.of(2026, 3, 10), true);

        when(shopRepository.findById(locationId)).thenReturn(Optional.empty());
        when(appointmentRepository.findByLocationIdAndStartAtLessThanAndEndAtGreaterThan(
                        any(UUID.class), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(shopRepository.existsById(locationId)).thenReturn(true);
        // Overlay data is local since #877: "unavailable" now means the staffing replica has
        // no assignment rows for the location.
        when(staffingScheduleService.hasAssignmentData(any(UUID.class))).thenReturn(false);

        ScheduleViewResponse response =
                appointmentsService.getScheduleView(request, UUID.fromString("00000000-0000-0000-0000-000000000011"));

        assertEquals("UNAVAILABLE", response.getAvailabilityOverlayStatus());
        assertEquals(List.of("HR_SYSTEM_UNAVAILABLE"), response.getWarnings());
        assertNotNull(response.getResources());
        assertTrue(response.getResources().isEmpty());
        assertFalse(response.getWarnings().isEmpty());
    }

    @Test
    void createAppointment_succeeds_andPersistsServiceRequests() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID serviceRequestId = UUID.fromString("00000000-0000-0000-0000-000000000031");

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(locationId);
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(vehicleId);
        request.setResourceId("tech-1");
        request.setStartAt(Instant.parse("2026-03-10T10:00:00Z"));
        request.setEndAt(Instant.parse("2026-03-10T11:00:00Z"));
        request.setServiceRequestIds(List.of(serviceRequestId));

        when(crmCustomerClient.getCustomerById(customerId))
                .thenReturn(java.util.Map.of("firstName", "Jane", "lastName", "Doe"));
        when(crmVehicleClient.getVehicleById(vehicleId))
                .thenReturn(java.util.Map.of("ownerCustomerId", customerId.toString()));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment saved = invocation.getArgument(0);
            saved.setAppointmentId(appointmentId);
            return saved;
        });
        when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(appointmentId))
                .thenReturn(List.of(AppointmentServiceRequest.builder()
                        .appointment(appointmentRef(appointmentId))
                        .serviceEntityId(serviceRequestId)
                        .build()));

        AppointmentResponse response = appointmentsService.createAppointment(request, null, null);

        assertEquals(appointmentId, response.getAppointmentId());
        assertEquals(AppointmentStatus.SCHEDULED.name(), response.getStatus());
        assertEquals(List.of(serviceRequestId), response.getServiceRequestIds());
        verify(appointmentRepository).save(any(Appointment.class));
        verify(appointmentServiceRequestRepository).saveAll(anyIterable());
    }

    @Test
    void createAppointment_throwsValidation_whenServiceRequestIdsMissing() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setCrmCustomerId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        request.setCrmVehicleId(UUID.fromString("11000000-0000-0000-0000-000000000001"));
        request.setStartAt(Instant.parse("2026-03-10T10:00:00Z"));
        request.setEndAt(Instant.parse("2026-03-10T11:00:00Z"));
        request.setServiceRequestIds(List.of());

        assertThrows(
                AppointmentValidationException.class, () -> appointmentsService.createAppointment(request, null, null));
    }

    @Test
    void createAppointment_throwsMismatch_whenVehicleOwnerDiffersFromCustomer() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        request.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000009"));
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000011"));
        request.setStartAt(Instant.parse("2026-03-10T10:00:00Z"));
        request.setEndAt(Instant.parse("2026-03-10T11:00:00Z"));
        request.setServiceRequestIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000111")));

        when(crmCustomerClient.getCustomerById(request.getCrmCustomerId()))
                .thenReturn(java.util.Map.of("firstName", "Jane"));
        when(crmVehicleClient.getVehicleById(request.getCrmVehicleId()))
                .thenReturn(java.util.Map.of(
                        "ownerCustomerId",
                        UUID.fromString("00000000-0000-0000-0000-000000000002").toString()));

        assertThrows(
                VehicleCustomerMismatchException.class,
                () -> appointmentsService.createAppointment(request, null, null));
    }

    @Test
    void createAppointment_returnsExisting_whenIdempotencyKeyMatchesSameRequest() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID serviceRequestId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        String idempotencyKey = "idem-123";

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(locationId);
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(vehicleId);
        request.setResourceId("tech-1");
        request.setStartAt(Instant.parse("2026-03-10T10:00:00Z"));
        request.setEndAt(Instant.parse("2026-03-10T11:00:00Z"));
        request.setWorkorderLinkRef("WO-1");
        request.setServiceRequestIds(List.of(serviceRequestId));

        Appointment existing = new Appointment();
        existing.setAppointmentId(appointmentId);
        existing.setStatus(AppointmentStatus.SCHEDULED);
        existing.setLocationId(locationId);
        existing.setResourceId("tech-1");
        existing.setCrmCustomerId(customerId);
        existing.setCrmVehicleId(vehicleId);
        existing.setStartAt(request.getStartAt());
        existing.setEndAt(request.getEndAt());
        existing.setWorkorderLinkRef("WO-1");
        existing.setIdempotencyKey(idempotencyKey);

        when(appointmentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));
        when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(appointmentId))
                .thenReturn(List.of(AppointmentServiceRequest.builder()
                        .appointment(appointmentRef(appointmentId))
                        .serviceEntityId(serviceRequestId)
                        .build()));

        AppointmentResponse response = appointmentsService.createAppointment(request, idempotencyKey, null);

        assertEquals(appointmentId, response.getAppointmentId());
        verify(appointmentRepository, never()).save(any(Appointment.class));
    }

    @Test
    void createAppointment_throwsValidation_whenIdempotencyKeyReusedWithDifferentRequest() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID existingServiceRequestId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        String idempotencyKey = "idem-123";

        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(locationId);
        request.setCrmCustomerId(customerId);
        request.setCrmVehicleId(vehicleId);
        request.setResourceId("tech-1");
        request.setStartAt(Instant.parse("2026-03-10T10:30:00Z"));
        request.setEndAt(Instant.parse("2026-03-10T11:30:00Z"));
        request.setServiceRequestIds(List.of(UUID.fromString("10000000-0000-0000-0000-000000000111")));

        Appointment existing = new Appointment();
        existing.setAppointmentId(appointmentId);
        existing.setStatus(AppointmentStatus.SCHEDULED);
        existing.setLocationId(locationId);
        existing.setResourceId("tech-1");
        existing.setCrmCustomerId(customerId);
        existing.setCrmVehicleId(vehicleId);
        existing.setStartAt(Instant.parse("2026-03-10T10:00:00Z"));
        existing.setEndAt(Instant.parse("2026-03-10T11:00:00Z"));
        existing.setIdempotencyKey(idempotencyKey);

        when(appointmentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));
        when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(appointmentId))
                .thenReturn(List.of(AppointmentServiceRequest.builder()
                        .appointment(appointmentRef(appointmentId))
                        .serviceEntityId(existingServiceRequestId)
                        .build()));

        assertThrows(
                AppointmentValidationException.class,
                () -> appointmentsService.createAppointment(request, idempotencyKey, null));
        verify(appointmentRepository, never()).save(any(Appointment.class));
    }

    @Test
    void loadCreateModel_delegatesToAppointmentLoadService() {
        UUID facilityId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AppointmentCreateModel expected = new AppointmentCreateModel();
        when(shopRepository.existsById(facilityId)).thenReturn(true);
        when(sourceEligibilityService.getWorkOrderStatus("wo-1", facilityId.toString()))
                .thenReturn("OPEN");
        when(appointmentLoadService.getFacilityTimeZoneId(facilityId)).thenReturn("UTC");
        UUID correlationId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        when(appointmentLoadService.loadCreateModel("WORKORDER", "wo-1", facilityId, correlationId))
                .thenReturn(expected);

        AppointmentCreateModel actual =
                appointmentsService.loadCreateModel("WORKORDER", "wo-1", facilityId, correlationId);

        assertEquals(facilityId.toString(), actual.getFacilityId());
        assertEquals("WORKORDER", actual.getSourceType());
        assertEquals("wo-1", actual.getSourceId());
        assertEquals("OPEN", actual.getSourceStatus());
        assertEquals("UTC", actual.getFacilityTimeZoneId());
        assertEquals("wo-1", actual.getWorkOrderId());
        verify(sourceEligibilityService).validateWorkOrderEligibility("wo-1", facilityId.toString());
        verify(appointmentLoadService).loadCreateModel("WORKORDER", "wo-1", facilityId, correlationId);
    }

    @Test
    void loadCreateModel_generatesCorrelationId_whenMissing() {
        UUID facilityId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AppointmentCreateModel expected = new AppointmentCreateModel();
        ArgumentCaptor<UUID> correlationCaptor = ArgumentCaptor.forClass(UUID.class);

        when(shopRepository.existsById(facilityId)).thenReturn(true);
        when(sourceEligibilityService.getWorkOrderStatus("wo-1", facilityId.toString()))
                .thenReturn("OPEN");
        when(appointmentLoadService.getFacilityTimeZoneId(facilityId)).thenReturn("UTC");
        when(appointmentLoadService.loadCreateModel(eq("WORKORDER"), eq("wo-1"), eq(facilityId), any(UUID.class)))
                .thenReturn(expected);

        appointmentsService.loadCreateModel("WORKORDER", "wo-1", facilityId, null);

        verify(appointmentLoadService)
                .loadCreateModel(eq("WORKORDER"), eq("wo-1"), eq(facilityId), correlationCaptor.capture());
        assertNotNull(correlationCaptor.getValue());
    }

    private ScheduleViewRequest scheduleRequest(UUID locationId, LocalDate date, boolean includeAvailabilityOverlay) {
        ScheduleViewRequest request = new ScheduleViewRequest();
        request.setLocationId(locationId);
        request.setDate(date);
        request.setIncludeAvailabilityOverlay(includeAvailabilityOverlay);
        request.setRange("FULL_DAY");
        return request;
    }

    private Appointment buildAppointment(UUID appointmentId, AppointmentStatus status, Instant startAt, Instant endAt) {
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(appointmentId);
        appointment.setStatus(status);
        appointment.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        appointment.setResourceId("resource-1");
        appointment.setCrmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000009"));
        appointment.setCrmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000011"));
        appointment.setStartAt(startAt);
        appointment.setEndAt(endAt);
        return appointment;
    }

    private Appointment appointmentRef(UUID appointmentId) {
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(appointmentId);
        return appointment;
    }
}
