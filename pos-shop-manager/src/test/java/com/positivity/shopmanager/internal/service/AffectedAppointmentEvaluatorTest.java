package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtMobileUnitReplica;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtVehicleReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DECISION-SHOPMGMT-022: an appointment is affected when it is SCHEDULED, starts in the future,
 * names a BAY or MOBILE_UNIT resource, and that resource is missing, not ACTIVE, or (a BAY) no
 * longer eligible.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AffectedAppointmentEvaluator (DECISION-SHOPMGMT-022)")
class AffectedAppointmentEvaluatorTest {

    private static final UUID LOCATION_ID = UUID.fromString("01960022-0000-7000-8000-000000000003");
    private static final UUID BAY_ID = UUID.fromString("01960022-0000-7000-8000-000000000010");
    private static final UUID MOBILE_UNIT_ID = UUID.fromString("01960022-0000-7000-8000-000000000011");
    private static final UUID APPOINTMENT_ID = UUID.fromString("01960022-0000-7000-8000-000000000020");
    private static final UUID VEHICLE_ID = UUID.fromString("01960022-0000-7000-8000-000000000002");
    private static final Instant FIXED_NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant FUTURE = Instant.parse("2026-06-18T08:00:00Z");
    private static final Instant PAST = Instant.parse("2026-01-01T08:00:00Z");

    @Mock
    private ExtBayReplicaRepository bayReplicaRepository;

    @Mock
    private ExtMobileUnitReplicaRepository mobileUnitReplicaRepository;

    @Mock
    private ExtCatalogServiceReplicaRepository catalogServiceRepository;

    @Mock
    private ExtVehicleReplicaRepository vehicleReplicaRepository;

    @Mock
    private AppointmentServiceRequestRepository appointmentServiceRequestRepository;

    @Mock
    private BayEligibilityService bayEligibilityService;

    private AffectedAppointmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AffectedAppointmentEvaluator(
                bayReplicaRepository,
                mobileUnitReplicaRepository,
                catalogServiceRepository,
                vehicleReplicaRepository,
                appointmentServiceRequestRepository,
                bayEligibilityService,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        // Every bay-candidate scenario needs these two batch reads answered; scenarios that never
        // reach a bay candidate (past, non-held, UNASSIGNED) never call them.
        org.mockito.Mockito.lenient()
                .when(appointmentServiceRequestRepository.findByAppointment_AppointmentIdIn(anyCollection()))
                .thenReturn(List.of());
    }

    private Appointment appointment(AppointmentStatus status, Instant startAt, String resourceId, String resourceType) {
        return Appointment.builder()
                .appointmentId(APPOINTMENT_ID)
                .locationId(LOCATION_ID)
                .status(status)
                .startAt(startAt)
                .resourceId(resourceId)
                .resourceType(resourceType)
                .crmVehicleId(VEHICLE_ID)
                .build();
    }

    private static ExtBayReplica bay(boolean active) {
        return ExtBayReplica.builder()
                .bayId(BAY_ID)
                .locationId(LOCATION_ID)
                .active(active)
                .build();
    }

    private static ExtMobileUnitReplica mobileUnit(boolean active) {
        return ExtMobileUnitReplica.builder()
                .mobileUnitId(MOBILE_UNIT_ID)
                .baseLocationId(LOCATION_ID)
                .active(active)
                .build();
    }

    @Test
    @DisplayName("a bay taken out of service makes its future SCHEDULED appointment affected")
    void outOfServiceBayIsAffected() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, FUTURE, BAY_ID.toString(), "BAY");
        when(bayReplicaRepository.findAllById(anyCollection())).thenReturn(List.of(bay(false)));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of());

        assertThat(evaluator.evaluateOne(appt)).isTrue();
    }

    @Test
    @DisplayName("a retired bay (also active=false in this replica) makes the appointment affected")
    void retiredBayIsAffected() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, FUTURE, BAY_ID.toString(), "BAY");
        when(bayReplicaRepository.findAllById(anyCollection())).thenReturn(List.of(bay(false)));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of());

        assertThat(evaluator.evaluateOne(appt)).isTrue();
    }

    @Test
    @DisplayName("a bay returned to service and still eligible clears the affected flag")
    void reactivatedEligibleBayIsNotAffected() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, FUTURE, BAY_ID.toString(), "BAY");
        ExtBayReplica activeBay = bay(true);
        when(bayReplicaRepository.findAllById(anyCollection())).thenReturn(List.of(activeBay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(activeBay));
        when(bayEligibilityService.refusalFor(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(evaluator.evaluateOne(appt)).isFalse();
    }

    @Test
    @DisplayName("a bay resourceId missing from the replica makes the appointment affected")
    void missingBayReplicaIsAffected() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, FUTURE, BAY_ID.toString(), "BAY");
        when(bayReplicaRepository.findAllById(anyCollection())).thenReturn(List.of());
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of());

        assertThat(evaluator.evaluateOne(appt)).isTrue();
    }

    @Test
    @DisplayName("an active bay that no longer passes DECISION-SHOPMGMT-021 eligibility is affected")
    void ineligibleBayIsAffected() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, FUTURE, BAY_ID.toString(), "BAY");
        ExtBayReplica activeBay = bay(true);
        when(bayReplicaRepository.findAllById(anyCollection())).thenReturn(List.of(activeBay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(activeBay));
        when(bayEligibilityService.refusalFor(any(), any(), any(), any()))
                .thenReturn(Optional.of(BayEligibilityService.Refusal.NOT_EQUIPPED));

        assertThat(evaluator.evaluateOne(appt)).isTrue();
    }

    @Test
    @DisplayName("a past appointment on an out-of-service bay is not affected")
    void pastAppointmentIsNotAffected() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, PAST, BAY_ID.toString(), "BAY");

        assertThat(evaluator.evaluateOne(appt)).isFalse();
        verify(bayReplicaRepository, never()).findAllById(anyCollection());
    }

    @Test
    @DisplayName("a non-held status (e.g. CHECKED_IN, already under way) is not affected regardless of the resource")
    void nonHeldStatusIsNotAffected() {
        Appointment appt = appointment(AppointmentStatus.CHECKED_IN, FUTURE, BAY_ID.toString(), "BAY");

        assertThat(evaluator.evaluateOne(appt)).isFalse();
        verify(bayReplicaRepository, never()).findAllById(anyCollection());
    }

    @Test
    @DisplayName("an UNASSIGNED appointment is never affected")
    void unassignedIsNotAffected() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, FUTURE, null, "UNASSIGNED");

        assertThat(evaluator.evaluateOne(appt)).isFalse();
        verify(bayReplicaRepository, never()).findAllById(anyCollection());
        verify(mobileUnitReplicaRepository, never()).findAllById(anyCollection());
    }

    @Test
    @DisplayName("the legacy TECHNICIAN resourceType reading is never affected")
    void technicianResourceTypeIsNotAffected() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, FUTURE, "tech-1", "TECHNICIAN");

        assertThat(evaluator.evaluateOne(appt)).isFalse();
    }

    @Test
    @DisplayName("a mobile unit missing from the replica makes the appointment affected")
    void missingMobileUnitIsAffected() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, FUTURE, MOBILE_UNIT_ID.toString(), "MOBILE_UNIT");
        when(mobileUnitReplicaRepository.findAllById(anyCollection())).thenReturn(List.of());

        assertThat(evaluator.evaluateOne(appt)).isTrue();
    }

    @Test
    @DisplayName("a mobile unit not ACTIVE makes the appointment affected")
    void inactiveMobileUnitIsAffected() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, FUTURE, MOBILE_UNIT_ID.toString(), "MOBILE_UNIT");
        when(mobileUnitReplicaRepository.findAllById(anyCollection())).thenReturn(List.of(mobileUnit(false)));

        assertThat(evaluator.evaluateOne(appt)).isTrue();
    }

    @Test
    @DisplayName("an active mobile unit is not affected")
    void activeMobileUnitIsNotAffected() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, FUTURE, MOBILE_UNIT_ID.toString(), "MOBILE_UNIT");
        when(mobileUnitReplicaRepository.findAllById(anyCollection())).thenReturn(List.of(mobileUnit(true)));

        assertThat(evaluator.evaluateOne(appt)).isFalse();
    }

    @Test
    @DisplayName("batch evaluate loads the location's bay roster once, not once per candidate appointment")
    void evaluateLoadsLocationBaysOncePerBatch() {
        Appointment first = appointment(AppointmentStatus.SCHEDULED, FUTURE, BAY_ID.toString(), "BAY");
        Appointment second = Appointment.builder()
                .appointmentId(UUID.fromString("01960022-0000-7000-8000-000000000021"))
                .locationId(LOCATION_ID)
                .status(AppointmentStatus.SCHEDULED)
                .startAt(FUTURE)
                .resourceId(BAY_ID.toString())
                .resourceType("BAY")
                .crmVehicleId(VEHICLE_ID)
                .build();
        ExtBayReplica activeBay = bay(true);
        when(bayReplicaRepository.findAllById(anyCollection())).thenReturn(List.of(activeBay));
        when(bayReplicaRepository.findActiveByLocationOrdered(LOCATION_ID)).thenReturn(List.of(activeBay));
        when(bayEligibilityService.refusalFor(any(), any(), any(), any())).thenReturn(Optional.empty());

        Map<UUID, Boolean> result = evaluator.evaluate(LOCATION_ID, List.of(first, second));

        assertThat(result.get(first.getAppointmentId())).isFalse();
        assertThat(result.get(second.getAppointmentId())).isFalse();
        verify(bayReplicaRepository, times(1)).findActiveByLocationOrdered(LOCATION_ID);
        verify(bayReplicaRepository, times(1)).findAllById(anyCollection());
    }

    @Test
    @DisplayName("evaluateOne mirrors evaluate for a single appointment and defaults an unlisted id to false")
    void evaluateOneDelegatesToEvaluate() {
        Appointment appt = appointment(AppointmentStatus.SCHEDULED, FUTURE, null, "UNASSIGNED");
        assertThat(evaluator.evaluateOne(appt)).isFalse();
    }
}
