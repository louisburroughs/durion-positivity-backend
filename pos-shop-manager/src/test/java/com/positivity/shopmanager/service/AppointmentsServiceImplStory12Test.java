package com.positivity.shopmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import com.positivity.shopmanager.internal.enums.AppointmentSourceType;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.event.AppointmentCreatedFromEstimateEvent;
import com.positivity.shopmanager.internal.event.AppointmentCreatedFromWorkOrderEvent;
import com.positivity.shopmanager.internal.exception.AppointmentValidationException;
import com.positivity.shopmanager.internal.exception.SourceNotEligibleException;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.RescheduleHistoryRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.internal.service.AppointmentsServiceImpl;

/**
 * Unit tests for CAP-249 Story #12: Create Appointment from Estimate or Work
 * Order.
 *
 * <p>
 * Verifies that {@link AppointmentsServiceImpl#createAppointment} enforces:
 * <ul>
 * <li>sourceType and sourceId are persisted on the appointment entity
 * (AC1).</li>
 * <li>Source-specific domain events are emitted for ESTIMATE and WORK_ORDER
 * sources (AC1).</li>
 * <li>No source-specific event is emitted when sourceType is null (AC5).</li>
 * <li>Missing sourceId when sourceType is provided throws
 * {@link AppointmentValidationException} (AC4).</li>
 * <li>Duplicate source detection throws {@link AppointmentValidationException}
 * (AC2).</li>
 * <li>Ineligible estimate or work order throws
 * {@link SourceNotEligibleException} (AC3).</li>
 * </ul>
 *
 * Issue: #12
 */
@ExtendWith(MockitoExtension.class)
class AppointmentsServiceImplStory12Test {
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
        private ApplicationEventPublisher eventPublisher;
        @Mock
        private ShopRepository shopRepository;
        @Mock
        private SourceEligibilityService sourceEligibilityService;

        private AppointmentsServiceImpl appointmentsService;

        private static final Instant FIXED_NOW = Instant.parse("2026-03-01T12:00:00Z");
        private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private static final UUID SERVICE_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private static final UUID SAVED_APPOINTMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private static final String ESTIMATE_ID = "EST-001";
        private static final String WORKORDER_ID = "WO-001";

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

                // Stub CRM clients for tests that reach the CRM call path (before source
                // validation)
                lenient().when(crmCustomerClient.getCustomerById(any(UUID.class))).thenReturn(Map.of());
                lenient().when(crmVehicleClient.getVehicleById(any(UUID.class))).thenReturn(Map.of());
        }

        // ─── AC1: Source fields persisted ────────────────────────────────────────

        /**
         * AC1: When sourceType=ESTIMATE and a valid sourceId are provided, the saved
         * Appointment entity must carry both fields so the source link is durable.
         */
        @Test
        void createAppointment_withEstimateSource_persistsSourceFields() {
                // Issue #12: verify sourceType/sourceId flow through to the persisted entity
                configureCommonSaveStub();
                when(sourceEligibilityService.getExistingAppointmentId(
                                eq(AppointmentSourceType.ESTIMATE.name()), eq(ESTIMATE_ID), any())).thenReturn(null);

                appointmentsService.createAppointment(buildRequest(AppointmentSourceType.ESTIMATE, ESTIMATE_ID), null,
                                null);

                ArgumentCaptor<Appointment> entityCaptor = ArgumentCaptor.forClass(Appointment.class);
                verify(appointmentRepository).save(entityCaptor.capture());
                Appointment saved = entityCaptor.getValue();
                assertThat(saved.getSourceType()).isEqualTo(AppointmentSourceType.ESTIMATE);
                assertThat(saved.getSourceId()).isEqualTo(ESTIMATE_ID);
        }

        // ─── AC1: Source-specific event emission ─────────────────────────────────

        /**
         * AC1: When sourceType=ESTIMATE, an {@link AppointmentCreatedFromEstimateEvent}
         * must be published carrying the correct appointmentId and estimateId.
         */
        @Test
        void createAppointment_withEstimateSource_emitsEstimateSourceEvent() {
                // Issue #12: verify AppointmentCreatedFromEstimateEvent emitted with correct
                // fields
                configureCommonSaveStub();
                when(sourceEligibilityService.getExistingAppointmentId(
                                eq(AppointmentSourceType.ESTIMATE.name()), eq(ESTIMATE_ID), any())).thenReturn(null);

                appointmentsService.createAppointment(buildRequest(AppointmentSourceType.ESTIMATE, ESTIMATE_ID), null,
                                null);

                // Two events are published: AppointmentCreatedEvent +
                // AppointmentCreatedFromEstimateEvent
                ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
                verify(eventPublisher, times(2)).publishEvent(captor.capture());

                List<Object> events = captor.getAllValues();
                AppointmentCreatedFromEstimateEvent sourceEvent = events.stream()
                                .filter(e -> e instanceof AppointmentCreatedFromEstimateEvent)
                                .map(e -> (AppointmentCreatedFromEstimateEvent) e)
                                .findFirst()
                                .orElseThrow(() -> new AssertionError(
                                                "AppointmentCreatedFromEstimateEvent not published"));

                assertThat(sourceEvent.appointmentId()).isEqualTo(SAVED_APPOINTMENT_ID);
                assertThat(sourceEvent.estimateId()).isEqualTo(ESTIMATE_ID);
                assertThat(sourceEvent.crmCustomerId()).isEqualTo(CUSTOMER_ID.toString());
                assertThat(sourceEvent.crmVehicleId()).isEqualTo(VEHICLE_ID.toString());
                assertThat(sourceEvent.createdAt()).isEqualTo(FIXED_NOW);
        }

        /**
         * AC1: When sourceType=WORK_ORDER, an
         * {@link AppointmentCreatedFromWorkOrderEvent}
         * must be published carrying the correct appointmentId and workorderId.
         */
        @Test
        void createAppointment_withWorkOrderSource_emitsWorkOrderSourceEvent() {
                // Issue #12: verify AppointmentCreatedFromWorkOrderEvent emitted with correct
                // fields
                configureCommonSaveStub();
                when(sourceEligibilityService.getExistingAppointmentId(
                                eq(AppointmentSourceType.WORK_ORDER.name()), eq(WORKORDER_ID), any())).thenReturn(null);

                appointmentsService.createAppointment(buildRequest(AppointmentSourceType.WORK_ORDER, WORKORDER_ID),
                                null, null);

                // Two events are published: AppointmentCreatedEvent +
                // AppointmentCreatedFromWorkOrderEvent
                ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
                verify(eventPublisher, times(2)).publishEvent(captor.capture());

                List<Object> events = captor.getAllValues();
                AppointmentCreatedFromWorkOrderEvent sourceEvent = events.stream()
                                .filter(e -> e instanceof AppointmentCreatedFromWorkOrderEvent)
                                .map(e -> (AppointmentCreatedFromWorkOrderEvent) e)
                                .findFirst()
                                .orElseThrow(() -> new AssertionError(
                                                "AppointmentCreatedFromWorkOrderEvent not published"));

                assertThat(sourceEvent.appointmentId()).isEqualTo(SAVED_APPOINTMENT_ID);
                assertThat(sourceEvent.workorderId()).isEqualTo(WORKORDER_ID);
                assertThat(sourceEvent.crmCustomerId()).isEqualTo(CUSTOMER_ID.toString());
                assertThat(sourceEvent.crmVehicleId()).isEqualTo(VEHICLE_ID.toString());
                assertThat(sourceEvent.createdAt()).isEqualTo(FIXED_NOW);
        }

        /**
         * AC1: When sourceType=WORK_ORDER and a valid sourceId are provided, the saved
         * Appointment entity must carry both fields so the source link is durable.
         */
        @Test
        void createAppointment_withWorkOrderSource_persistsSourceFields() {
                // Issue #12: verify sourceType/sourceId (WORK_ORDER) flow through to the
                // persisted entity
                configureCommonSaveStub();
                when(sourceEligibilityService.getExistingAppointmentId(
                                eq(AppointmentSourceType.WORK_ORDER.name()), eq(WORKORDER_ID), any())).thenReturn(null);

                appointmentsService.createAppointment(buildRequest(AppointmentSourceType.WORK_ORDER, WORKORDER_ID),
                                null, null);

                ArgumentCaptor<Appointment> entityCaptor = ArgumentCaptor.forClass(Appointment.class);
                verify(appointmentRepository).save(entityCaptor.capture());
                Appointment saved = entityCaptor.getValue();
                assertThat(saved.getSourceType()).isEqualTo(AppointmentSourceType.WORK_ORDER);
                assertThat(saved.getSourceId()).isEqualTo(WORKORDER_ID);
        }

        // ─── AC5: Null sourceType — standard flow, no source-specific events ──────

        /**
         * AC5: When sourceType is null, neither source-specific event is emitted and
         * no eligibility service interaction occurs.
         */
        @Test
        void createAppointment_withoutSource_emitsNoSourceEvent() {
                // Issue #12: verify source-specific events are NOT emitted when sourceType=null
                configureCommonSaveStub();

                appointmentsService.createAppointment(buildRequest(null, null), null, null);

                // Only the standard AppointmentCreatedEvent should be published (exactly once)
                ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
                verify(eventPublisher, times(1)).publishEvent(captor.capture());
                assertThat(captor.getValue())
                                .isNotInstanceOf(AppointmentCreatedFromEstimateEvent.class)
                                .isNotInstanceOf(AppointmentCreatedFromWorkOrderEvent.class);

                verify(sourceEligibilityService, never()).getExistingAppointmentId(any(), any(), any());
                verify(sourceEligibilityService, never()).validateEstimateEligibility(any(), any());
                verify(sourceEligibilityService, never()).validateWorkOrderEligibility(any(), any());
        }

        @Test
        void createAppointment_withNullSourceTypeAndOrphanSourceId_savesWithNullSourceId() {
                // Fix r2870236946: sourceId must not be persisted when sourceType is null
                configureCommonSaveStub();

                AppointmentCreateRequest request = buildRequest(null, null);
                // Manually set a sourceId on a null-sourceType request to exercise the guard
                request.setSourceId("orphan-id");

                appointmentsService.createAppointment(request, null, null);

                ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
                verify(appointmentRepository).save(captor.capture());
                assertThat(captor.getValue().getSourceType()).isNull();
                assertThat(captor.getValue().getSourceId()).isNull();
        }

        // ─── AC4: sourceType without sourceId → validation error ─────────────────

        /**
         * AC4: Providing sourceType=ESTIMATE without a sourceId must throw
         * {@link AppointmentValidationException} before any persistence or event
         * emission.
         */
        @Test
        void createAppointment_withSourceTypeButNoSourceId_throwsValidationException() {
                // Issue #12: sourceId is mandatory when sourceType is provided
                assertThatThrownBy(() -> appointmentsService
                                .createAppointment(buildRequest(AppointmentSourceType.ESTIMATE, null), null, null))
                                .isInstanceOf(AppointmentValidationException.class)
                                .hasMessageContaining("sourceId");

                verify(appointmentRepository, never()).save(any());
                verify(eventPublisher, never()).publishEvent(any());
        }

        /**
         * AC4: Providing sourceType=ESTIMATE with a blank sourceId must also throw
         * {@link AppointmentValidationException}.
         */
        @Test
        void createAppointment_withSourceTypeAndBlankSourceId_throwsValidationException() {
                // Issue #12: blank sourceId is treated identically to absent sourceId
                assertThatThrownBy(() -> appointmentsService
                                .createAppointment(buildRequest(AppointmentSourceType.ESTIMATE, "  "), null, null))
                                .isInstanceOf(AppointmentValidationException.class)
                                .hasMessageContaining("sourceId");

                verify(appointmentRepository, never()).save(any());
        }

        // ─── AC2: Duplicate source detection ─────────────────────────────────────

        /**
         * AC2: When {@code getExistingAppointmentId} returns a non-null appointmentId,
         * {@link AppointmentValidationException} must be thrown before any save.
         */
        @Test
        void createAppointment_whenDuplicateSourceExists_throwsValidationException() {
                // Issue #12: guard against creating a second appointment for the same source
                // entity
                when(sourceEligibilityService.getExistingAppointmentId(
                                eq(AppointmentSourceType.ESTIMATE.name()), eq(ESTIMATE_ID), any()))
                                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());

                assertThatThrownBy(() -> appointmentsService
                                .createAppointment(buildRequest(AppointmentSourceType.ESTIMATE, ESTIMATE_ID), null,
                                                null))
                                .isInstanceOf(AppointmentValidationException.class)
                                .hasMessageContaining("appointment already exists");

                verify(appointmentRepository, never()).save(any());
                verify(eventPublisher, never()).publishEvent(any());
        }

        // ─── AC3: Source eligibility validation ──────────────────────────────────

        /**
         * AC3: When {@code validateEstimateEligibility} throws
         * {@link SourceNotEligibleException},
         * t must propagate to the caller without saving the appointment.
         */
        @Test
        void createAppointment_whenEstimateNotEligible_throwsSourceNotEligibleException() {
                // Issue #12: ineligible estimate must surface as SourceNotEligibleException
                // (422)
                when(sourceEligibilityService.getExistingAppointmentId(
                                eq(AppointmentSourceType.ESTIMATE.name()), eq(ESTIMATE_ID), any())).thenReturn(null);
                doThrow(new SourceNotEligibleException(
                                "ESTIMATE_NOT_ELIGIBLE",
                                "Estimate EST-001 is not eligible for scheduling",
                                "ESTIMATE",
                                ESTIMATE_ID,
                                "DRAFT"))
                                .when(sourceEligibilityService).validateEstimateEligibility(eq(ESTIMATE_ID), any());

                assertThatThrownBy(() -> appointmentsService
                                .createAppointment(buildRequest(AppointmentSourceType.ESTIMATE, ESTIMATE_ID), null,
                                                null))
                                .isInstanceOf(SourceNotEligibleException.class)
                                .hasMessageContaining("EST-001");

                verify(appointmentRepository, never()).save(any());
                verify(eventPublisher, never()).publishEvent(any());
        }

        /**
         * AC3: When {@code validateWorkOrderEligibility} throws
         * {@link SourceNotEligibleException},
         * it must propagate to the caller without saving the appointment.
         */
        @Test
        void createAppointment_whenWorkOrderNotEligible_throwsSourceNotEligibleException() {
                // Issue #12: ineligible workorder must surface as SourceNotEligibleException
                // (422)
                when(sourceEligibilityService.getExistingAppointmentId(
                                eq(AppointmentSourceType.WORK_ORDER.name()), eq(WORKORDER_ID), any())).thenReturn(null);
                doThrow(new SourceNotEligibleException(
                                "WORKORDER_NOT_ELIGIBLE",
                                "Work order WO-001 is not eligible for scheduling",
                                "WORK_ORDER",
                                WORKORDER_ID,
                                "COMPLETED"))
                                .when(sourceEligibilityService).validateWorkOrderEligibility(eq(WORKORDER_ID), any());

                assertThatThrownBy(() -> appointmentsService
                                .createAppointment(buildRequest(AppointmentSourceType.WORK_ORDER, WORKORDER_ID), null,
                                                null))
                                .isInstanceOf(SourceNotEligibleException.class)
                                .hasMessageContaining("WO-001");

                verify(appointmentRepository, never()).save(any());
                verify(eventPublisher, never()).publishEvent(any());
        }

        // ─── Idempotency (CAP-249 Story #12 follow-up) ────────────────────────

        @Test
        void createAppointment_withMatchingIdempotentRequest_returnsExistingAppointment() {
                Appointment existing = Appointment.builder()
                                .appointmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .idempotencyKey("test-key")
                                .status(AppointmentStatus.SCHEDULED)
                                .locationId(LOCATION_ID)
                                .resourceId("BAY-01")
                                .crmCustomerId(CUSTOMER_ID)
                                .crmVehicleId(VEHICLE_ID)
                                .startAt(Instant.parse("2026-04-01T10:00:00Z"))
                                .endAt(Instant.parse("2026-04-01T11:00:00Z"))
                                .workorderLinkRef(null)
                                .build();

                when(appointmentRepository.findByIdempotencyKey("test-key"))
                                .thenReturn(java.util.Optional.of(existing));
                when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(existing.getAppointmentId()))
                                .thenReturn(List.of(AppointmentServiceRequest.builder()
                                                .appointment(existing)
                                                .serviceEntityId(SERVICE_REQUEST_ID)
                                                .build()));

                AppointmentCreateRequest request = buildRequest(null, null);
                AppointmentResponse response = appointmentsService.createAppointment(request, "test-key", null);

                assertThat(response.getAppointmentId()).isEqualTo(existing.getAppointmentId());
                verify(appointmentRepository, never()).save(any());
        }

        @Test
        void createAppointment_withMismatchedIdempotentRequest_throwsValidationException() {
                Appointment existing = Appointment.builder()
                                .idempotencyKey("test-key")
                                .status(AppointmentStatus.SCHEDULED)
                                .startAt(Instant.now(TEST_CLOCK))
                                .build();
                when(appointmentRepository.findByIdempotencyKey("test-key"))
                                .thenReturn(java.util.Optional.of(existing));

                AppointmentCreateRequest request = buildRequest(null, null); // Different startAt
                assertThatThrownBy(() -> appointmentsService.createAppointment(request, "test-key", null))
                                .isInstanceOf(AppointmentValidationException.class)
                                .hasMessageContaining("Idempotency-Key was already used with a different request");
        }

        // ─── Helpers ─────────────────────────────────────────────────────────────

        /**
         * Builds a minimal valid {@link AppointmentCreateRequest} with optional source
         * fields.
         *
         * @param sourceType the source type, or {@code null} for a standard appointment
         * @param sourceId   the source identifier, or {@code null}
         * @return a fully populated request ready for service invocation
         */
        private AppointmentCreateRequest buildRequest(AppointmentSourceType sourceType, String sourceId) {
                AppointmentCreateRequest request = new AppointmentCreateRequest();
                request.setLocationId(LOCATION_ID);
                request.setCrmCustomerId(CUSTOMER_ID);
                request.setCrmVehicleId(VEHICLE_ID);
                request.setResourceId("BAY-01");
                request.setStartAt(Instant.parse("2026-04-01T10:00:00Z"));
                request.setEndAt(Instant.parse("2026-04-01T11:00:00Z"));
                request.setServiceRequestIds(List.of(SERVICE_REQUEST_ID));
                request.setSourceType(sourceType);
                request.setSourceId(sourceId);
                return request;
        }

        /**
         * Stubs {@code appointmentRepository.save()} to assign
         * {@link #SAVED_APPOINTMENT_ID}
         * to the entity, mirroring real JPA ID generation behaviour.
         */
        private void configureCommonSaveStub() {
                when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
                        Appointment apt = inv.getArgument(0);
                        apt.setAppointmentId(SAVED_APPOINTMENT_ID);
                        return apt;
                });
        }

        // ─── Additional coverage for response mapping ────────────────────────────

        @Test
        void createAppointment_withNullShopTimezone_doesNotThrow() {
                // Issue #12: verify null safety for timezone resolution
                configureCommonSaveStub();

                // This should not throw an exception, even with a missing shop/timezone.
                // The service defaults to UTC internally.
                var response = appointmentsService.createAppointment(buildRequest(null, null), null, null);
                assertThat(response.getAppointmentId()).isEqualTo(SAVED_APPOINTMENT_ID);
        }

        @Test
        void createAppointment_withNullCustomerSnapshot_handlesGracefully() {
                // Issue #12: verify null safety for customer snapshot parsing
                when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
                        Appointment apt = inv.getArgument(0);
                        apt.setAppointmentId(SAVED_APPOINTMENT_ID);
                        apt.setCustomerSnapshot(null); // Force null snapshot
                        return apt;
                });

                var response = appointmentsService.createAppointment(buildRequest(null, null), null, null);

                assertThat(response.getCustomerSnapshot()).isEmpty();
        }
}
