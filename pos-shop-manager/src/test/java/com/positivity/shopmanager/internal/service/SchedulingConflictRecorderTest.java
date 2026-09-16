package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.dto.AppointmentConflictView;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import com.positivity.shopmanager.internal.entity.ConflictRule;
import com.positivity.shopmanager.internal.entity.SchedulingConflict;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.ConflictResourceType;
import com.positivity.shopmanager.internal.enums.ConflictSeverity;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.ConflictOverrideRepository;
import com.positivity.shopmanager.internal.repository.SchedulingConflictRepository;
import com.positivity.shopmanager.internal.service.SchedulingConflictEvaluator.BookingAttempt;
import com.positivity.shopmanager.internal.service.SchedulingConflictEvaluator.DetectedConflict;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The conflict rows the booking path writes and the keyless-duplicate identity it checks (CAP-326). */
@ExtendWith(MockitoExtension.class)
class SchedulingConflictRecorderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID EXISTING = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID VEHICLE = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID SR_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SR_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Instant START = Instant.parse("2026-06-16T15:00:00Z");
    private static final Instant END = Instant.parse("2026-06-16T16:00:00Z");

    @Mock
    private SchedulingConflictRepository schedulingConflictRepository;

    @Mock
    private ConflictOverrideRepository conflictOverrideRepository;

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private AppointmentServiceRequestRepository appointmentServiceRequestRepository;

    @Mock
    private SchedulingConflictEvaluator evaluator;

    private SchedulingConflictRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new SchedulingConflictRecorder(
                schedulingConflictRepository,
                conflictOverrideRepository,
                appointmentRepository,
                appointmentServiceRequestRepository,
                evaluator,
                CLOCK);
        lenient().when(schedulingConflictRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    @DisplayName("findKeylessDuplicate — the identity tuple minus the key (spec D17 item 3)")
    class KeylessDuplicate {

        @BeforeEach
        void anExistingBooking() {
            lenient()
                    .when(appointmentRepository.findHeldOverlappingAtLocation(any(), any(), any(), any()))
                    .thenReturn(List.of(existing(e -> {})));
            lenient()
                    .when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(EXISTING))
                    .thenReturn(List.of(serviceRequest(SR_A), serviceRequest(SR_B)));
        }

        @Test
        void anExactResubmissionIsFound_serviceRequestOrderIgnored() {
            AppointmentCreateRequest request = request(r -> r.setServiceRequestIds(List.of(SR_B, SR_A)));
            assertThat(recorder.findKeylessDuplicate(request))
                    .map(Appointment::getAppointmentId)
                    .contains(EXISTING);
        }

        @Test
        void aDifferentCustomerIsNotADuplicate() {
            assertThat(recorder.findKeylessDuplicate(request(r -> r.setCrmCustomerId(UUID.randomUUID()))))
                    .isEmpty();
        }

        @Test
        void aDifferentVehicleIsNotADuplicate() {
            assertThat(recorder.findKeylessDuplicate(request(r -> r.setCrmVehicleId(UUID.randomUUID()))))
                    .isEmpty();
        }

        @Test
        void aDifferentStartIsNotADuplicate() {
            assertThat(recorder.findKeylessDuplicate(request(r -> r.setStartAt(START.plusSeconds(60)))))
                    .isEmpty();
        }

        @Test
        void aDifferentEndIsNotADuplicate() {
            assertThat(recorder.findKeylessDuplicate(request(r -> r.setEndAt(END.plusSeconds(60)))))
                    .isEmpty();
        }

        @Test
        void aDifferentResourceIsNotADuplicate() {
            assertThat(recorder.findKeylessDuplicate(request(r -> r.setResourceId("bay-2"))))
                    .isEmpty();
        }

        @Test
        void aDifferentWorkorderLinkIsNotADuplicate() {
            assertThat(recorder.findKeylessDuplicate(request(r -> r.setWorkorderLinkRef("WO-other"))))
                    .isEmpty();
        }

        @Test
        void differentServiceRequestsAreNotADuplicate() {
            assertThat(recorder.findKeylessDuplicate(request(r -> r.setServiceRequestIds(List.of(SR_A)))))
                    .isEmpty();
        }

        @Test
        void onlyAppointmentsStillHoldingTheirSlotAreConsulted() {
            recorder.findKeylessDuplicate(request(r -> {}));
            verify(appointmentRepository)
                    .findHeldOverlappingAtLocation(LOCATION, START, END, AppointmentStatus.holdingAResource());
        }
    }

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        void aRefusedAttemptIsRecordedWithoutAnAppointment() {
            DetectedConflict closed = detected("FACILITY_CLOSED", ConflictSeverity.HARD);
            BookingAttempt attempt = new BookingAttempt(LOCATION, "bay-1", START, END, null);

            recorder.recordRefused(attempt, List.of(closed));

            ArgumentCaptor<SchedulingConflict> captor = ArgumentCaptor.forClass(SchedulingConflict.class);
            verify(schedulingConflictRepository).save(captor.capture());
            SchedulingConflict row = captor.getValue();
            assertThat(row.getAppointment()).isNull();
            assertThat(row.getSeverity()).isEqualTo(ConflictSeverity.HARD);
            assertThat(row.getConflictRule().getCode()).isEqualTo("FACILITY_CLOSED");
            assertThat(row.getLocationId()).isEqualTo(LOCATION);
            assertThat(row.getAttemptedStartAt()).isEqualTo(START);
            assertThat(row.getAttemptedEndAt()).isEqualTo(END);
            assertThat(row.getDetectedAt()).isEqualTo(Instant.now(CLOCK));
            verify(appointmentRepository, never()).findById(any());
        }

        @Test
        void aRefusedRescheduleIsRecordedAgainstTheAppointmentItTriedToMove() {
            Appointment appointment = existing(e -> {});
            when(appointmentRepository.findById(EXISTING)).thenReturn(Optional.of(appointment));
            BookingAttempt attempt = new BookingAttempt(LOCATION, "bay-1", START, END, EXISTING);

            recorder.recordRefused(attempt, List.of(detected("BAY_DOUBLE_BOOKED", ConflictSeverity.HARD)));

            ArgumentCaptor<SchedulingConflict> captor = ArgumentCaptor.forClass(SchedulingConflict.class);
            verify(schedulingConflictRepository).save(captor.capture());
            assertThat(captor.getValue().getAppointment()).isSameAs(appointment);
        }

        @Test
        void theExclusionConstraintsRefusalIsRecordedAsBayDoubleBooked() {
            BookingAttempt attempt = new BookingAttempt(LOCATION, "bay-1", START, END, null);
            DetectedConflict overlap = detected("BAY_DOUBLE_BOOKED", ConflictSeverity.HARD);
            when(evaluator.bayDoubleBooked(attempt)).thenReturn(overlap);

            assertThat(recorder.recordRefusedOverlap(attempt)).isSameAs(overlap);
            verify(schedulingConflictRepository).save(any());
        }

        @Test
        void softConflictsAreRecordedAgainstTheBookedAppointment() {
            Appointment appointment = existing(e -> {});
            DetectedConflict soft = detected("FACILITY_NEAR_CAPACITY", ConflictSeverity.SOFT);

            List<SchedulingConflict> rows = recorder.recordAccepted(appointment, List.of(soft));

            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.getAppointment()).isSameAs(appointment);
                assertThat(row.getSeverity()).isEqualTo(ConflictSeverity.SOFT);
                assertThat(row.getAttemptedStartAt()).isEqualTo(appointment.getStartAt());
            });
        }

        @Test
        void aHardConflictIsNeverRecordedAgainstABooking() {
            assertThatThrownBy(() -> recorder.recordAccepted(
                            existing(e -> {}), List.of(detected("BAY_DOUBLE_BOOKED", ConflictSeverity.HARD))))
                    .isInstanceOf(IllegalStateException.class);
            verify(schedulingConflictRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("views: overridable until overridden, in detection order")
    void viewsCarryTheOverrideState() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        UUID second = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
        SchedulingConflict earlier = conflictRow(first, "MECHANIC_OVERTIME", Instant.parse("2026-06-15T10:00:00Z"));
        SchedulingConflict later = conflictRow(second, "FACILITY_NEAR_CAPACITY", Instant.parse("2026-06-15T11:00:00Z"));
        when(schedulingConflictRepository.findByAppointment_AppointmentId(EXISTING))
                .thenReturn(List.of(later, earlier));
        when(conflictOverrideRepository.existsByConflict_Id(first)).thenReturn(true);
        when(conflictOverrideRepository.existsByConflict_Id(second)).thenReturn(false);

        List<AppointmentConflictView> views = recorder.viewsFor(EXISTING);

        assertThat(views)
                .extracting(AppointmentConflictView::getCode)
                .containsExactly("MECHANIC_OVERTIME", "FACILITY_NEAR_CAPACITY");
        assertThat(views.get(0).isOverridden()).isTrue();
        assertThat(views.get(0).isOverridable()).isFalse();
        assertThat(views.get(1).isOverridden()).isFalse();
        assertThat(views.get(1).isOverridable()).isTrue();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private static Appointment existing(Consumer<Appointment> tweak) {
        Appointment appointment = Appointment.builder()
                .appointmentId(EXISTING)
                .status(AppointmentStatus.SCHEDULED)
                .locationId(LOCATION)
                .resourceId("bay-1")
                .crmCustomerId(CUSTOMER)
                .crmVehicleId(VEHICLE)
                .startAt(START)
                .endAt(END)
                .workorderLinkRef("WO-1")
                .createdAt(Instant.parse("2026-06-15T09:00:00Z"))
                .build();
        tweak.accept(appointment);
        return appointment;
    }

    private static AppointmentCreateRequest request(Consumer<AppointmentCreateRequest> tweak) {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setLocationId(LOCATION);
        request.setResourceId("bay-1");
        request.setCrmCustomerId(CUSTOMER);
        request.setCrmVehicleId(VEHICLE);
        request.setStartAt(START);
        request.setEndAt(END);
        request.setWorkorderLinkRef("WO-1");
        request.setServiceRequestIds(List.of(SR_A, SR_B));
        tweak.accept(request);
        return request;
    }

    private static AppointmentServiceRequest serviceRequest(UUID serviceEntityId) {
        return AppointmentServiceRequest.builder()
                .serviceEntityId(serviceEntityId)
                .build();
    }

    private static DetectedConflict detected(String code, ConflictSeverity severity) {
        ConflictRule rule = ConflictRule.builder()
                .id(UUID.nameUUIDFromBytes(code.getBytes()))
                .code(code)
                .severity(severity)
                .resourceType(ConflictResourceType.BAY)
                .messageTemplate(code)
                .build();
        return new DetectedConflict(rule, "bay-1", code + " fired");
    }

    private static SchedulingConflict conflictRow(UUID id, String code, Instant detectedAt) {
        return SchedulingConflict.builder()
                .id(id)
                .conflictRule(detected(code, ConflictSeverity.SOFT).rule())
                .severity(ConflictSeverity.SOFT)
                .locationId(LOCATION)
                .resourceId("bay-1")
                .attemptedStartAt(START)
                .attemptedEndAt(END)
                .detail(code)
                .detectedAt(detectedAt)
                .build();
    }
}
