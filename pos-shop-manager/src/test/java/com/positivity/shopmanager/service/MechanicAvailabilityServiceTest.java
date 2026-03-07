package com.positivity.shopmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.client.HrAvailabilityClient;
import com.positivity.shopmanager.internal.dto.HrScheduleBlock;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.TravelBlock;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.exception.HrUnavailableException;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.TravelBlockRepository;
import com.positivity.shopmanager.internal.service.MechanicAvailabilityServiceImpl;
import com.positivity.shopmanager.service.dto.MechanicAvailabilityResult;
import com.positivity.shopmanager.service.enums.AvailabilityStatus;
import com.positivity.shopmanager.service.enums.ConflictReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class MechanicAvailabilityServiceTest {

        private static final String PERSON_ID = "HR-7001";

        // Window: 10:00–14:00 on 2026-06-15
        private static final Instant WINDOW_START = Instant.parse("2026-06-15T10:00:00Z");
        private static final Instant WINDOW_END = Instant.parse("2026-06-15T14:00:00Z");

        @Mock
        private MechanicRepository mechanicRepository;
        @Mock
        private AppointmentRepository appointmentRepository;
        @Mock
        private TravelBlockRepository travelBlockRepository;
        @Mock
        private HrAvailabilityClient hrAvailabilityClient;

        private MechanicAvailabilityServiceImpl service;

        @BeforeEach
        void setUp() {
                service = new MechanicAvailabilityServiceImpl(
                                mechanicRepository, appointmentRepository, travelBlockRepository, hrAvailabilityClient);
        }

        // -------------------------------------------------------------------------
        // AC1 – Mechanic fully available
        // -------------------------------------------------------------------------

        /**
         * AC1: When the HR system returns a shift covering the full window, no PTO
         * overlaps, no appointments, and no travel blocks, the mechanic is AVAILABLE
         * with an empty conflict list.
         */
        @Test
        void ac1_fullyAvailableWithCoveringShift_returnsAvailable() {
                Mechanic mechanic = buildMechanic();
                when(mechanicRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(mechanic));
                // HR returns one SHIFT block that fully covers the window
                when(hrAvailabilityClient.getScheduleBlocks(eq(PERSON_ID), any(), any()))
                                .thenReturn(List.of(
                                                HrScheduleBlock.builder()
                                                                .blockType("SHIFT")
                                                                .startTime(Instant.parse("2026-06-15T08:00:00Z"))
                                                                .endTime(Instant.parse("2026-06-15T17:00:00Z"))
                                                                .build()));
                when(appointmentRepository.findByResourceIdAndResourceTypeAndStartAtLessThanAndEndAtGreaterThan(
                                anyString(), anyString(), any(), any())).thenReturn(List.of());
                when(travelBlockRepository.findOverlapping(anyString(), any(), any())).thenReturn(List.of());

                MechanicAvailabilityResult result = service.queryAvailability(PERSON_ID, WINDOW_START, WINDOW_END);

                assertThat(result.getOverallStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
                assertThat(result.getConflicts()).isEmpty();
                assertThat(result.getPersonId()).isEqualTo(PERSON_ID);
        }

        // -------------------------------------------------------------------------
        // AC2 – Mechanic on PTO
        // -------------------------------------------------------------------------

        /**
         * AC2: When the HR system returns a PTO block overlapping the window,
         * the mechanic is UNAVAILABLE with an ON_PTO conflict entry.
         */
        @Test
        void ac2_mechanicOnPto_returnsUnavailableWithPtoConflict() {
                Mechanic mechanic = buildMechanic();
                when(mechanicRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(mechanic));
                when(hrAvailabilityClient.getScheduleBlocks(eq(PERSON_ID), any(), any()))
                                .thenReturn(List.of(
                                                HrScheduleBlock.builder()
                                                                .blockType("PTO")
                                                                .startTime(Instant.parse("2026-06-15T00:00:00Z"))
                                                                .endTime(Instant.parse("2026-06-15T23:59:59Z"))
                                                                .build()));
                when(appointmentRepository.findByResourceIdAndResourceTypeAndStartAtLessThanAndEndAtGreaterThan(
                                anyString(), anyString(), any(), any())).thenReturn(List.of());
                when(travelBlockRepository.findOverlapping(anyString(), any(), any())).thenReturn(List.of());

                MechanicAvailabilityResult result = service.queryAvailability(PERSON_ID, WINDOW_START, WINDOW_END);

                assertThat(result.getOverallStatus()).isEqualTo(AvailabilityStatus.UNAVAILABLE);
                assertThat(result.getConflicts())
                                .anyMatch(c -> c.getReasonCode() == ConflictReasonCode.ON_PTO);
        }

        // -------------------------------------------------------------------------
        // AC3 – Off shift (no covering shift in HR data)
        // -------------------------------------------------------------------------

        /**
         * AC3: When the HR system returns no SHIFT block covering the window,
         * the mechanic is UNAVAILABLE with an OFF_SHIFT conflict entry.
         */
        @Test
        void ac3_offShift_returnsUnavailableWithOffShiftConflict() {
                Mechanic mechanic = buildMechanic();
                when(mechanicRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(mechanic));
                // HR returns empty schedule — no shifts, no PTO
                when(hrAvailabilityClient.getScheduleBlocks(eq(PERSON_ID), any(), any()))
                                .thenReturn(List.of());
                when(appointmentRepository.findByResourceIdAndResourceTypeAndStartAtLessThanAndEndAtGreaterThan(
                                anyString(), anyString(), any(), any())).thenReturn(List.of());
                when(travelBlockRepository.findOverlapping(anyString(), any(), any())).thenReturn(List.of());

                MechanicAvailabilityResult result = service.queryAvailability(PERSON_ID, WINDOW_START, WINDOW_END);

                assertThat(result.getOverallStatus()).isEqualTo(AvailabilityStatus.UNAVAILABLE);
                assertThat(result.getConflicts())
                                .anyMatch(c -> c.getReasonCode() == ConflictReasonCode.OFF_SHIFT);
        }

        // -------------------------------------------------------------------------
        // AC4 – Appointment conflict (partial overlap → PARTIALLY_AVAILABLE)
        // -------------------------------------------------------------------------

        /**
         * AC4: When the mechanic has a covering shift but an appointment partially
         * overlaps the window, the mechanic is PARTIALLY_AVAILABLE with an
         * ASSIGNED_APPOINTMENT conflict entry.
         */
        @Test
        void ac4_appointmentPartiallyOverlaps_returnsPartiallyAvailable() {
                Mechanic mechanic = buildMechanic();
                when(mechanicRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(mechanic));
                when(hrAvailabilityClient.getScheduleBlocks(eq(PERSON_ID), any(), any()))
                                .thenReturn(List.of(
                                                HrScheduleBlock.builder()
                                                                .blockType("SHIFT")
                                                                .startTime(Instant.parse("2026-06-15T08:00:00Z"))
                                                                .endTime(Instant.parse("2026-06-15T17:00:00Z"))
                                                                .build()));
                // Appointment: 11:00–12:00 (partial)
                Appointment appt = buildAppointment(
                                Instant.parse("2026-06-15T11:00:00Z"),
                                Instant.parse("2026-06-15T12:00:00Z"));
                when(appointmentRepository.findByResourceIdAndResourceTypeAndStartAtLessThanAndEndAtGreaterThan(
                                anyString(), anyString(), any(), any())).thenReturn(List.of(appt));
                when(travelBlockRepository.findOverlapping(anyString(), any(), any())).thenReturn(List.of());

                MechanicAvailabilityResult result = service.queryAvailability(PERSON_ID, WINDOW_START, WINDOW_END);

                assertThat(result.getOverallStatus()).isEqualTo(AvailabilityStatus.PARTIALLY_AVAILABLE);
                assertThat(result.getConflicts())
                                .anyMatch(c -> c.getReasonCode() == ConflictReasonCode.ASSIGNED_APPOINTMENT);
        }

        // -------------------------------------------------------------------------
        // AC5 – Travel block conflict
        // -------------------------------------------------------------------------

        /**
         * AC5: When the mechanic has a covering shift but a travel block overlaps
         * the window, the mechanic is PARTIALLY_AVAILABLE with a TRAVEL_BLOCK conflict.
         */
        @Test
        void ac5_travelBlockOverlaps_returnsTravelBlockConflict() {
                Mechanic mechanic = buildMechanic();
                when(mechanicRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(mechanic));
                when(hrAvailabilityClient.getScheduleBlocks(eq(PERSON_ID), any(), any()))
                                .thenReturn(List.of(
                                                HrScheduleBlock.builder()
                                                                .blockType("SHIFT")
                                                                .startTime(Instant.parse("2026-06-15T08:00:00Z"))
                                                                .endTime(Instant.parse("2026-06-15T17:00:00Z"))
                                                                .build()));
                when(appointmentRepository.findByResourceIdAndResourceTypeAndStartAtLessThanAndEndAtGreaterThan(
                                anyString(), anyString(), any(), any())).thenReturn(List.of());
                TravelBlock block = TravelBlock.builder()
                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .personId(PERSON_ID)
                                .startTime(Instant.parse("2026-06-15T10:30:00Z"))
                                .endTime(Instant.parse("2026-06-15T12:00:00Z"))
                                .description("Drive to customer site")
                                .build();
                when(travelBlockRepository.findOverlapping(anyString(), any(), any())).thenReturn(List.of(block));

                MechanicAvailabilityResult result = service.queryAvailability(PERSON_ID, WINDOW_START, WINDOW_END);

                assertThat(result.getOverallStatus()).isEqualTo(AvailabilityStatus.PARTIALLY_AVAILABLE);
                assertThat(result.getConflicts())
                                .anyMatch(c -> c.getReasonCode() == ConflictReasonCode.TRAVEL_BLOCK);
        }

        // -------------------------------------------------------------------------
        // AC6 – HR system unavailable
        // -------------------------------------------------------------------------

        /**
         * AC6: When the HR system throws a RestClientException, the client wraps it
         * in HrUnavailableException so GlobalExceptionHandler maps it to HTTP 503
         * with code HR_UNAVAILABLE.
         */
        @Test
        void ac6_hrSystemUnresponsive_throwsHrUnavailableException() {
                Mechanic mechanic = buildMechanic();
                when(mechanicRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(mechanic));
                when(hrAvailabilityClient.getScheduleBlocks(eq(PERSON_ID), any(), any()))
                                .thenThrow(new HrUnavailableException("HR system is unavailable: Connection refused",
                                                new RestClientException("Connection refused")));

                assertThatThrownBy(() -> service.queryAvailability(PERSON_ID, WINDOW_START, WINDOW_END))
                                .isInstanceOf(HrUnavailableException.class);
        }

        // -------------------------------------------------------------------------
        // AC7 – Invalid window (startTime >= endTime)
        // -------------------------------------------------------------------------

        /**
         * AC7: When windowStart is not before windowEnd, the service must throw
         * IllegalArgumentException before calling any downstream system.
         */
        @Test
        void ac7_windowStartAfterEnd_throwsIllegalArgument() {
                assertThatThrownBy(() -> service.queryAvailability(PERSON_ID, WINDOW_END, WINDOW_START))
                                .isInstanceOf(IllegalArgumentException.class);

                // Guard must fire before any repository or HR client interaction
                verifyNoInteractions(mechanicRepository, appointmentRepository, travelBlockRepository,
                                hrAvailabilityClient);
        }

        // -------------------------------------------------------------------------
        // Coverage: mechanic not found in repository
        // -------------------------------------------------------------------------

        /**
         * When personId does not match any mechanic in the local repository, the
         * service throws IllegalArgumentException before querying any downstream
         * system.
         */
        @Test
        void mechanicNotFound_throwsIllegalArgument() {
                when(mechanicRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> service.queryAvailability(PERSON_ID, WINDOW_START, WINDOW_END))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining(PERSON_ID);

                verifyNoInteractions(hrAvailabilityClient, appointmentRepository, travelBlockRepository);
        }

        // -------------------------------------------------------------------------
        // Fixture helpers
        // -------------------------------------------------------------------------

        private Mechanic buildMechanic() {
                return Mechanic.builder()
                                .mechanicId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .personId(PERSON_ID)
                                .firstName("Test")
                                .lastName("Mechanic")
                                .status(MechanicStatus.ACTIVE)
                                .version(1)
                                .build();
        }

        private Appointment buildAppointment(Instant startAt, Instant endAt) {
                return Appointment.builder()
                                .appointmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .resourceId(PERSON_ID)
                                .resourceType("MECHANIC")
                                .startAt(startAt)
                                .endAt(endAt)
                                .build();
        }
}
