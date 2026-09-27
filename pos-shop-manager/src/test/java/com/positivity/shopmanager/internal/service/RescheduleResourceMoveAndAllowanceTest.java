package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.RescheduleHistory;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.shopmanager.internal.enums.ResourceType;
import com.positivity.shopmanager.internal.exception.RescheduleApprovalReasonRequiredException;
import com.positivity.shopmanager.internal.exception.ServicePositionEligibilityException;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

/**
 * DECISION-SHOPMGMT-022 rule 3 (reschedule to a different resource) and DECISION-SHOPMGMT-004 (the
 * reschedule allowance and its shop-caused exemption).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentsServiceImpl reschedule: resource move and allowance")
class RescheduleResourceMoveAndAllowanceTest {

    private static final UUID LOCATION_ID = UUID.fromString("01960033-0000-7000-8000-000000000003");
    private static final UUID CUSTOMER_ID = UUID.fromString("01960033-0000-7000-8000-000000000001");
    private static final UUID VEHICLE_ID = UUID.fromString("01960033-0000-7000-8000-000000000002");
    private static final UUID SERVICE_REQUEST_ID = UUID.fromString("01960033-0000-7000-8000-000000000004");
    private static final UUID OLD_BAY_ID = UUID.fromString("01960033-0000-7000-8000-000000000010");
    private static final UUID NEW_BAY_ID = UUID.fromString("01960033-0000-7000-8000-000000000011");
    private static final UUID APPOINTMENT_ID = UUID.fromString("01960033-0000-7000-8000-000000000020");
    private static final Instant FIXED_NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant START_AT = Instant.parse("2026-06-18T08:00:00Z");
    private static final Instant END_AT = Instant.parse("2026-06-18T10:00:00Z");
    private static final Instant NEW_START_AT = Instant.parse("2026-06-19T08:00:00Z");
    private static final Instant NEW_END_AT = Instant.parse("2026-06-19T10:00:00Z");

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

    @Mock
    private AffectedAppointmentEvaluator affectedAppointmentEvaluator;

    @Mock
    private RescheduleApprovalGuard rescheduleApprovalGuard;

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
                skillRequirementResolver,
                affectedAppointmentEvaluator,
                rescheduleApprovalGuard);

        lenient().when(affectedAppointmentEvaluator.evaluateOne(any())).thenReturn(false);
        lenient()
                .when(rescheduleHistoryRepository.countByAppointmentIdAndCountsAgainstAllowanceTrue(any()))
                .thenReturn(0L);
    }

    private Appointment scheduledAppointment(UUID bayId) {
        Appointment appointment = Appointment.builder()
                .appointmentId(APPOINTMENT_ID)
                .status(AppointmentStatus.SCHEDULED)
                .locationId(LOCATION_ID)
                .resourceId(bayId.toString())
                .resourceType("BAY")
                .crmCustomerId(CUSTOMER_ID)
                .crmVehicleId(VEHICLE_ID)
                .startAt(START_AT)
                .endAt(END_AT)
                .build();
        when(appointmentRepository.findByIdForUpdate(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));
        lenient()
                .when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient()
                .when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(APPOINTMENT_ID))
                .thenReturn(List.of(AppointmentServiceRequest.builder()
                        .serviceEntityId(SERVICE_REQUEST_ID)
                        .build()));
        return appointment;
    }

    private static ExtBayReplica bay(UUID id, boolean active) {
        return ExtBayReplica.builder()
                .bayId(id)
                .locationId(LOCATION_ID)
                .active(active)
                .build();
    }

    private RescheduleAppointmentRequest baseRequest() {
        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest();
        request.setNewStartAt(NEW_START_AT);
        request.setNewEndAt(NEW_END_AT);
        request.setReason(RescheduleReasonCode.CUSTOMER_REQUEST);
        return request;
    }

    // ── Resource move (DECISION-SHOPMGMT-022 rule 3) ────────────────────────────────────────────

    @Test
    @DisplayName("reschedule naming a new, eligible bay succeeds and the resourceId changes")
    void rescheduleToAnotherEligibleBaySucceedsAndChangesResourceId() {
        scheduledAppointment(OLD_BAY_ID);
        ExtBayReplica newBay = bay(NEW_BAY_ID, true);
        when(bayReplicaRepository.findById(NEW_BAY_ID)).thenReturn(Optional.of(newBay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(newBay));
        when(bayEligibilityService.refusalFor(eq(newBay), any(), any(), any())).thenReturn(Optional.empty());

        RescheduleAppointmentRequest request = baseRequest();
        request.setNewResourceId(NEW_BAY_ID);
        request.setNewResourceType(ResourceType.BAY);

        AppointmentResponse response = appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request);

        assertThat(response.getResourceId()).isEqualTo(NEW_BAY_ID.toString());
        verify(bayReplicaRepository, never()).findById(OLD_BAY_ID);
    }

    @Test
    @DisplayName("reschedule naming an ineligible new bay is 422 with the DECISION-SHOPMGMT-021 code")
    void rescheduleToAnIneligibleBayIs422ServicePositionCode() {
        scheduledAppointment(OLD_BAY_ID);
        ExtBayReplica newBay = bay(NEW_BAY_ID, true);
        when(bayReplicaRepository.findById(NEW_BAY_ID)).thenReturn(Optional.of(newBay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(newBay));
        when(bayEligibilityService.refusalFor(eq(newBay), any(), any(), any()))
                .thenReturn(Optional.of(BayEligibilityService.Refusal.NOT_EQUIPPED));

        RescheduleAppointmentRequest request = baseRequest();
        request.setNewResourceId(NEW_BAY_ID);

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request))
                .isInstanceOfSatisfying(
                        ServicePositionEligibilityException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(ServicePositionEligibilityException.Code.SERVICE_POSITION_NOT_EQUIPPED));
        verify(rescheduleHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("moving off an out-of-service bay succeeds — only the new resource is validated, never the old one")
    void movingOffAnOutOfServiceBaySucceeds() {
        // OLD_BAY_ID is never looked up at all by this reschedule: re-validating it (the old
        // behaviour) would refuse the very move meant to fix it.
        scheduledAppointment(OLD_BAY_ID);
        ExtBayReplica newBay = bay(NEW_BAY_ID, true);
        when(bayReplicaRepository.findById(NEW_BAY_ID)).thenReturn(Optional.of(newBay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(newBay));
        when(bayEligibilityService.refusalFor(eq(newBay), any(), any(), any())).thenReturn(Optional.empty());

        RescheduleAppointmentRequest request = baseRequest();
        request.setNewResourceId(NEW_BAY_ID);
        request.setNewResourceType(ResourceType.BAY);

        AppointmentResponse response = appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request);

        assertThat(response.getResourceId()).isEqualTo(NEW_BAY_ID.toString());
        verify(bayReplicaRepository, never()).findById(OLD_BAY_ID);
    }

    // ── Allowance (DECISION-SHOPMGMT-004) ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the 3rd non-exempt reschedule without appointments:reschedule:approve is denied (403)")
    void thirdNonExemptRescheduleWithoutPermissionIsDenied() {
        scheduledAppointment(OLD_BAY_ID);
        when(rescheduleHistoryRepository.countByAppointmentIdAndCountsAgainstAllowanceTrue(APPOINTMENT_ID))
                .thenReturn(2L);
        doThrow(new AccessDeniedException("missing appointments:reschedule:approve"))
                .when(rescheduleApprovalGuard)
                .requireApprovalPermission();

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(APPOINTMENT_ID, baseRequest()))
                .isInstanceOf(AccessDeniedException.class);
        verify(rescheduleHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("the 3rd non-exempt reschedule with permission but no approvalReason is refused")
    void thirdNonExemptRescheduleWithPermissionButNoApprovalReasonIsRefused() {
        scheduledAppointment(OLD_BAY_ID);
        when(rescheduleHistoryRepository.countByAppointmentIdAndCountsAgainstAllowanceTrue(APPOINTMENT_ID))
                .thenReturn(2L);
        // rescheduleApprovalGuard is a no-op mock: the caller holds the permission.

        assertThatThrownBy(() -> appointmentsService.rescheduleAppointment(APPOINTMENT_ID, baseRequest()))
                .isInstanceOf(RescheduleApprovalReasonRequiredException.class);
        verify(rescheduleHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("the 3rd non-exempt reschedule with permission and a non-blank approvalReason succeeds")
    void thirdNonExemptRescheduleWithPermissionAndApprovalReasonSucceeds() {
        scheduledAppointment(OLD_BAY_ID);
        when(rescheduleHistoryRepository.countByAppointmentIdAndCountsAgainstAllowanceTrue(APPOINTMENT_ID))
                .thenReturn(2L);
        ExtBayReplica oldBay = bay(OLD_BAY_ID, true);
        when(bayReplicaRepository.findById(OLD_BAY_ID)).thenReturn(Optional.of(oldBay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(oldBay));
        when(bayEligibilityService.refusalFor(eq(oldBay), any(), any(), any())).thenReturn(Optional.empty());

        RescheduleAppointmentRequest request = baseRequest();
        request.setApprovalReason("Manager approved a 3rd reschedule");

        appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request);

        ArgumentCaptor<RescheduleHistory> captor = ArgumentCaptor.forClass(RescheduleHistory.class);
        verify(rescheduleHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().isCountsAgainstAllowance()).isTrue();
        assertThat(captor.getValue().getApprovalReason()).isEqualTo("Manager approved a 3rd reschedule");
    }

    @Test
    @DisplayName("an EQUIPMENT_ISSUE reschedule is exempt: no permission check, and it does not count")
    void equipmentIssueRescheduleIsExemptAndDoesNotCount() {
        scheduledAppointment(OLD_BAY_ID);
        // The allowance count is never even read for an exempt reschedule (enforceRescheduleAllowance
        // returns before checking it), so no count stub is needed here.
        ExtBayReplica oldBay = bay(OLD_BAY_ID, true);
        when(bayReplicaRepository.findById(OLD_BAY_ID)).thenReturn(Optional.of(oldBay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(oldBay));
        when(bayEligibilityService.refusalFor(eq(oldBay), any(), any(), any())).thenReturn(Optional.empty());

        RescheduleAppointmentRequest request = baseRequest();
        request.setReason(RescheduleReasonCode.EQUIPMENT_ISSUE);

        appointmentsService.rescheduleAppointment(APPOINTMENT_ID, request);

        verify(rescheduleApprovalGuard, never()).requireApprovalPermission();
        ArgumentCaptor<RescheduleHistory> captor = ArgumentCaptor.forClass(RescheduleHistory.class);
        verify(rescheduleHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().isCountsAgainstAllowance()).isFalse();
    }

    @Test
    @DisplayName("a reschedule of an already-affected appointment is exempt, judged before any change")
    void rescheduleOfAnAffectedAppointmentIsExempt() {
        Appointment appointment = scheduledAppointment(OLD_BAY_ID);
        when(affectedAppointmentEvaluator.evaluateOne(appointment)).thenReturn(true);
        // Exempt, so — as above — the allowance count is never read.
        ExtBayReplica oldBay = bay(OLD_BAY_ID, true);
        when(bayReplicaRepository.findById(OLD_BAY_ID)).thenReturn(Optional.of(oldBay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(oldBay));
        when(bayEligibilityService.refusalFor(eq(oldBay), any(), any(), any())).thenReturn(Optional.empty());

        appointmentsService.rescheduleAppointment(APPOINTMENT_ID, baseRequest());

        verify(rescheduleApprovalGuard, never()).requireApprovalPermission();
        ArgumentCaptor<RescheduleHistory> captor = ArgumentCaptor.forClass(RescheduleHistory.class);
        verify(rescheduleHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().isCountsAgainstAllowance()).isFalse();
    }

    @Test
    @DisplayName("the 1st and 2nd reschedules need no permission at all, even when not exempt")
    void firstAndSecondReschedulesNeedNoPermission() {
        ExtBayReplica oldBay = bay(OLD_BAY_ID, true);
        lenient().when(bayReplicaRepository.findById(OLD_BAY_ID)).thenReturn(Optional.of(oldBay));
        lenient()
                .when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID))
                .thenReturn(List.of(oldBay));
        lenient()
                .when(bayEligibilityService.refusalFor(eq(oldBay), any(), any(), any()))
                .thenReturn(Optional.empty());

        scheduledAppointment(OLD_BAY_ID);
        when(rescheduleHistoryRepository.countByAppointmentIdAndCountsAgainstAllowanceTrue(APPOINTMENT_ID))
                .thenReturn(1L);

        appointmentsService.rescheduleAppointment(APPOINTMENT_ID, baseRequest());

        verify(rescheduleApprovalGuard, never()).requireApprovalPermission();
        ArgumentCaptor<RescheduleHistory> captor = ArgumentCaptor.forClass(RescheduleHistory.class);
        verify(rescheduleHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().isCountsAgainstAllowance()).isTrue();
    }
}
