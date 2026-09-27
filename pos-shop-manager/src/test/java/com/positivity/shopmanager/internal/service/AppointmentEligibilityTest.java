package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtMobileUnitReplica;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.shopmanager.internal.enums.ResourceType;
import com.positivity.shopmanager.internal.exception.ServicePositionEligibilityException;
import com.positivity.shopmanager.internal.exception.ServicePositionEligibilityException.Code;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.RescheduleHistoryRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * DECISION-SHOPMGMT-021: appointment create and reschedule refuse a BAY or MOBILE_UNIT resource
 * that fails the shared {@link BayEligibilityService} rule, and persist {@code resourceType}
 * (DECISION-SHOPMGMT-003). {@link BayEligibilityService} itself is mocked here — its own rules are
 * proven directly in {@link BayEligibilityServiceTest} — so these tests cover only
 * {@link AppointmentsServiceImpl}'s orchestration: which checks run for which resourceType, in
 * which order, and which {@code SERVICE_POSITION_*} code each refusal maps to.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentsServiceImpl bay/mobile-unit eligibility (DECISION-SHOPMGMT-021)")
class AppointmentEligibilityTest {

    private static final UUID LOCATION_ID = UUID.fromString("01960003-0000-7000-8000-000000000003");
    private static final UUID OTHER_LOCATION_ID = UUID.fromString("01960003-0000-7000-8000-000000000099");
    private static final UUID CUSTOMER_ID = UUID.fromString("01960003-0000-7000-8000-000000000001");
    private static final UUID VEHICLE_ID = UUID.fromString("01960003-0000-7000-8000-000000000002");
    private static final UUID SERVICE_REQUEST_ID = UUID.fromString("01960003-0000-7000-8000-000000000004");
    private static final UUID BAY_ID = UUID.fromString("01960003-0000-7000-8000-000000000010");
    private static final UUID MOBILE_UNIT_ID = UUID.fromString("01960003-0000-7000-8000-000000000011");
    private static final UUID APPOINTMENT_ID = UUID.fromString("01960003-0000-7000-8000-000000000020");
    private static final Instant FIXED_NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant START_AT = Instant.parse("2026-06-18T08:00:00Z");
    private static final Instant END_AT = Instant.parse("2026-06-18T10:00:00Z");

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

    @Mock
    private ExtBayReplicaRepository bayReplicaRepository;

    @Mock
    private ExtMobileUnitReplicaRepository mobileUnitReplicaRepository;

    @Mock
    private BayEligibilityService bayEligibilityService;

    @Mock
    private SkillRequirementResolver skillRequirementResolver;

    private final SchedulingConflictEvaluator conflictEvaluator = mock(SchedulingConflictEvaluator.class);
    private final SchedulingConflictRecorder conflictRecorder = mock(SchedulingConflictRecorder.class);

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
                workOrderAppointmentMappingRepository,
                conflictEvaluator,
                conflictRecorder,
                new BookingHorizonPolicy(180),
                bayReplicaRepository,
                mobileUnitReplicaRepository,
                bayEligibilityService,
                skillRequirementResolver);

        lenient().when(crmSnapshotService.getCustomerById(any(UUID.class))).thenReturn(Map.of());
        lenient().when(crmSnapshotService.getVehicleById(any(UUID.class))).thenReturn(Map.of());
        lenient().when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setAppointmentId(APPOINTMENT_ID);
            return appointment;
        });
    }

    private AppointmentCreateRequest createRequest(ResourceType resourceType, String resourceId) {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setCrmCustomerId(CUSTOMER_ID);
        request.setCrmVehicleId(VEHICLE_ID);
        request.setLocationId(LOCATION_ID);
        request.setResourceId(resourceId);
        request.setResourceType(resourceType);
        request.setStartAt(START_AT);
        request.setEndAt(END_AT);
        request.setServiceRequestIds(List.of(SERVICE_REQUEST_ID));
        return request;
    }

    private static ExtBayReplica activeBay(UUID id, UUID locationId) {
        return ExtBayReplica.builder()
                .bayId(id)
                .locationId(locationId)
                .active(true)
                .build();
    }

    private static ExtMobileUnitReplica activeMobileUnit(UUID id, UUID baseLocationId) {
        return ExtMobileUnitReplica.builder()
                .mobileUnitId(id)
                .baseLocationId(baseLocationId)
                .active(true)
                .build();
    }

    // ── UNASSIGNED: no resource checks ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("UNASSIGNED (explicit) runs no resource checks and is persisted verbatim")
    void unassignedRunsNoChecks() {
        appointmentsService.createAppointment(createRequest(ResourceType.UNASSIGNED, "garbage"), null, null);

        verify(bayReplicaRepository, never()).findById(any());
        verify(mobileUnitReplicaRepository, never()).findById(any());
        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        assertThat(captor.getValue().getResourceType()).isEqualTo("UNASSIGNED");
    }

    @Test
    @DisplayName("resourceType omitted defaults to UNASSIGNED: persisted, no resource checks")
    void omittedResourceTypeDefaultsToUnassigned() {
        AppointmentCreateRequest request = createRequest(null, null);

        appointmentsService.createAppointment(request, null, null);

        verify(bayReplicaRepository, never()).findById(any());
        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        assertThat(captor.getValue().getResourceType()).isEqualTo("UNASSIGNED");
    }

    // ── BAY ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BAY resourceId that is not a UUID -> 422 SERVICE_POSITION_INVALID")
    void bayResourceIdNotAUuidIsInvalid() {
        assertThatThrownBy(() ->
                        appointmentsService.createAppointment(createRequest(ResourceType.BAY, "BAY-04"), null, null))
                .isInstanceOfSatisfying(
                        ServicePositionEligibilityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.SERVICE_POSITION_INVALID));
    }

    @Test
    @DisplayName("BAY unknown to the replica -> 422 SERVICE_POSITION_INVALID")
    void unknownBayIsInvalid() {
        when(bayReplicaRepository.findById(BAY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentsService.createAppointment(
                        createRequest(ResourceType.BAY, BAY_ID.toString()), null, null))
                .isInstanceOfSatisfying(
                        ServicePositionEligibilityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.SERVICE_POSITION_INVALID));
    }

    @Test
    @DisplayName("BAY at another location -> 422 SERVICE_POSITION_INVALID (never falls back to general bays)")
    void bayAtAnotherLocationIsInvalid() {
        when(bayReplicaRepository.findById(BAY_ID)).thenReturn(Optional.of(activeBay(BAY_ID, OTHER_LOCATION_ID)));

        assertThatThrownBy(() -> appointmentsService.createAppointment(
                        createRequest(ResourceType.BAY, BAY_ID.toString()), null, null))
                .isInstanceOfSatisfying(
                        ServicePositionEligibilityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.SERVICE_POSITION_INVALID));
    }

    @Test
    @DisplayName("BAY out of service (not ACTIVE) -> 422 SERVICE_POSITION_INACTIVE")
    void inactiveBayIsInactive() {
        ExtBayReplica bay = ExtBayReplica.builder()
                .bayId(BAY_ID)
                .locationId(LOCATION_ID)
                .active(false)
                .build();
        when(bayReplicaRepository.findById(BAY_ID)).thenReturn(Optional.of(bay));

        assertThatThrownBy(() -> appointmentsService.createAppointment(
                        createRequest(ResourceType.BAY, BAY_ID.toString()), null, null))
                .isInstanceOfSatisfying(
                        ServicePositionEligibilityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.SERVICE_POSITION_INACTIVE));
    }

    @Test
    @DisplayName("a specialty operation no active bay at the location claims -> 422 SERVICE_POSITION_NOT_EQUIPPED"
            + " (e.g. no alignment bay at this location)")
    void bayNotEquippedForSpecialtyOperation() {
        ExtBayReplica bay = activeBay(BAY_ID, LOCATION_ID);
        when(bayReplicaRepository.findById(BAY_ID)).thenReturn(Optional.of(bay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(bay));
        when(bayEligibilityService.operationCodesOf(any())).thenReturn(Set.of("WHEEL-ALIGNMENT-4-WHEEL"));
        when(bayEligibilityService.refusalFor(eq(bay), any(), any(), any()))
                .thenReturn(Optional.of(BayEligibilityService.Refusal.NOT_EQUIPPED));

        assertThatThrownBy(() -> appointmentsService.createAppointment(
                        createRequest(ResourceType.BAY, BAY_ID.toString()), null, null))
                .isInstanceOfSatisfying(
                        ServicePositionEligibilityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.SERVICE_POSITION_NOT_EQUIPPED));
    }

    @Test
    @DisplayName("class 7 vehicle on a bay with maxDutyClass 3 -> 422 SERVICE_POSITION_DUTY_CLASS_EXCEEDED")
    void bayDutyClassExceeded() {
        ExtBayReplica bay = activeBay(BAY_ID, LOCATION_ID);
        when(bayReplicaRepository.findById(BAY_ID)).thenReturn(Optional.of(bay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(bay));
        when(skillRequirementResolver.gvwrClassOf(VEHICLE_ID)).thenReturn(7);
        when(bayEligibilityService.refusalFor(eq(bay), any(), any(), eq(7)))
                .thenReturn(Optional.of(BayEligibilityService.Refusal.DUTY_CLASS_EXCEEDED));

        assertThatThrownBy(() -> appointmentsService.createAppointment(
                        createRequest(ResourceType.BAY, BAY_ID.toString()), null, null))
                .isInstanceOfSatisfying(
                        ServicePositionEligibilityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.SERVICE_POSITION_DUTY_CLASS_EXCEEDED));
    }

    @Test
    @DisplayName("an unknown vehicle GVWR class skips the duty-class check and the booking proceeds")
    void unknownGvwrClassPasses() {
        ExtBayReplica bay = activeBay(BAY_ID, LOCATION_ID);
        when(bayReplicaRepository.findById(BAY_ID)).thenReturn(Optional.of(bay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(bay));
        when(skillRequirementResolver.gvwrClassOf(VEHICLE_ID)).thenReturn(null);
        when(bayEligibilityService.refusalFor(eq(bay), any(), any(), isNull())).thenReturn(Optional.empty());

        appointmentsService.createAppointment(createRequest(ResourceType.BAY, BAY_ID.toString()), null, null);

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        assertThat(captor.getValue().getResourceType()).isEqualTo("BAY");
        assertThat(captor.getValue().getResourceId()).isEqualTo(BAY_ID.toString());
    }

    @Test
    @DisplayName("an eligible bay is persisted with resourceType BAY")
    void eligibleBaySucceeds() {
        ExtBayReplica bay = activeBay(BAY_ID, LOCATION_ID);
        when(bayReplicaRepository.findById(BAY_ID)).thenReturn(Optional.of(bay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(bay));
        when(bayEligibilityService.refusalFor(eq(bay), any(), any(), any())).thenReturn(Optional.empty());

        appointmentsService.createAppointment(createRequest(ResourceType.BAY, BAY_ID.toString()), null, null);

        verify(appointmentRepository).save(any(Appointment.class));
    }

    // ── MOBILE_UNIT: existence, location and active only — no specialty/duty-class ─────────────────

    @Test
    @DisplayName("MOBILE_UNIT unknown -> 422 SERVICE_POSITION_INVALID")
    void unknownMobileUnitIsInvalid() {
        when(mobileUnitReplicaRepository.findById(MOBILE_UNIT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentsService.createAppointment(
                        createRequest(ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID.toString()), null, null))
                .isInstanceOfSatisfying(
                        ServicePositionEligibilityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.SERVICE_POSITION_INVALID));
    }

    @Test
    @DisplayName("MOBILE_UNIT based at another location -> 422 SERVICE_POSITION_INVALID")
    void mobileUnitAtAnotherLocationIsInvalid() {
        when(mobileUnitReplicaRepository.findById(MOBILE_UNIT_ID))
                .thenReturn(Optional.of(activeMobileUnit(MOBILE_UNIT_ID, OTHER_LOCATION_ID)));

        assertThatThrownBy(() -> appointmentsService.createAppointment(
                        createRequest(ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID.toString()), null, null))
                .isInstanceOfSatisfying(
                        ServicePositionEligibilityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.SERVICE_POSITION_INVALID));
    }

    @Test
    @DisplayName("MOBILE_UNIT not ACTIVE -> 422 SERVICE_POSITION_INACTIVE")
    void inactiveMobileUnitIsInactive() {
        ExtMobileUnitReplica unit = ExtMobileUnitReplica.builder()
                .mobileUnitId(MOBILE_UNIT_ID)
                .baseLocationId(LOCATION_ID)
                .active(false)
                .build();
        when(mobileUnitReplicaRepository.findById(MOBILE_UNIT_ID)).thenReturn(Optional.of(unit));

        assertThatThrownBy(() -> appointmentsService.createAppointment(
                        createRequest(ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID.toString()), null, null))
                .isInstanceOfSatisfying(
                        ServicePositionEligibilityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.SERVICE_POSITION_INACTIVE));
    }

    @Test
    @DisplayName("an active MOBILE_UNIT at the right location succeeds with no specialty/duty-class check")
    void activeMobileUnitAtOwnLocationSucceeds() {
        when(mobileUnitReplicaRepository.findById(MOBILE_UNIT_ID))
                .thenReturn(Optional.of(activeMobileUnit(MOBILE_UNIT_ID, LOCATION_ID)));

        appointmentsService.createAppointment(
                createRequest(ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID.toString()), null, null);

        verify(bayEligibilityService, never()).refusalFor(any(), any(), any(), any());
        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        assertThat(captor.getValue().getResourceType()).isEqualTo("MOBILE_UNIT");
    }

    // ── Reschedule: same rule, against the appointment's own (unchanged) resource ──────────────────

    private Appointment scheduledAppointment(String resourceId, String resourceType) {
        Appointment appointment = Appointment.builder()
                .appointmentId(APPOINTMENT_ID)
                .status(AppointmentStatus.SCHEDULED)
                .locationId(LOCATION_ID)
                .resourceId(resourceId)
                .resourceType(resourceType)
                .crmCustomerId(CUSTOMER_ID)
                .crmVehicleId(VEHICLE_ID)
                .startAt(START_AT)
                .endAt(END_AT)
                .build();
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));
        when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(APPOINTMENT_ID))
                .thenReturn(List.of(AppointmentServiceRequest.builder()
                        .serviceEntityId(SERVICE_REQUEST_ID)
                        .build()));
        return appointment;
    }

    private RescheduleAppointmentRequest rescheduleRequest() {
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(Instant.parse("2026-06-19T08:00:00Z"));
        request.setNewEndAt(Instant.parse("2026-06-19T10:00:00Z"));
        request.setReason(RescheduleReasonCode.CUSTOMER_REQUEST);
        return request;
    }

    @Test
    @DisplayName("reschedule re-runs the same eligibility rule against the appointment's own resource: a bay that"
            + " went out of service since booking now refuses the reschedule")
    void rescheduleRefusesWhenTheOwnBayIsNowInactive() {
        scheduledAppointment(BAY_ID.toString(), "BAY");
        ExtBayReplica bay = ExtBayReplica.builder()
                .bayId(BAY_ID)
                .locationId(LOCATION_ID)
                .active(false)
                .build();
        when(bayReplicaRepository.findById(BAY_ID)).thenReturn(Optional.of(bay));

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(APPOINTMENT_ID, rescheduleRequest()))
                .isInstanceOfSatisfying(
                        ServicePositionEligibilityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.SERVICE_POSITION_INACTIVE));
        // The window is not moved and no history is recorded for a refused reschedule.
        verify(rescheduleHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("reschedule of an appointment whose bay is still eligible succeeds and moves the window")
    void rescheduleSucceedsWhenTheOwnBayIsStillEligible() {
        scheduledAppointment(BAY_ID.toString(), "BAY");
        ExtBayReplica bay = activeBay(BAY_ID, LOCATION_ID);
        when(bayReplicaRepository.findById(BAY_ID)).thenReturn(Optional.of(bay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(bay));
        when(bayEligibilityService.refusalFor(eq(bay), any(), any(), any())).thenReturn(Optional.empty());

        appointmentsService.rescheduleAppointment(APPOINTMENT_ID, rescheduleRequest());

        verify(rescheduleHistoryRepository).save(any());
    }

    @Test
    @DisplayName("reschedule of a legacy appointment with no stored resourceType (never written) runs no resource"
            + " checks — existing appointments are not re-validated")
    void rescheduleOfLegacyNullResourceTypeSkipsChecks() {
        scheduledAppointment(BAY_ID.toString(), null);

        appointmentsService.rescheduleAppointment(APPOINTMENT_ID, rescheduleRequest());

        verify(bayReplicaRepository, never()).findById(any());
        verify(rescheduleHistoryRepository).save(any());
    }

    @Test
    @DisplayName("reschedule of an UNASSIGNED appointment runs no resource checks")
    void rescheduleOfUnassignedAppointmentSkipsChecks() {
        scheduledAppointment(null, "UNASSIGNED");

        appointmentsService.rescheduleAppointment(APPOINTMENT_ID, rescheduleRequest());

        verify(bayReplicaRepository, never()).findById(any());
        verify(mobileUnitReplicaRepository, never()).findById(any());
        verify(rescheduleHistoryRepository).save(any());
    }
}
