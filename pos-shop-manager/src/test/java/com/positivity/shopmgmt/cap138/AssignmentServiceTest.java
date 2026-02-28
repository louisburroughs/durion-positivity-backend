package com.positivity.shopmgmt.cap138;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AssignmentMechanic;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.AssignmentStatusEnum;
import com.positivity.shopmanager.internal.enums.MechanicRoleEnum;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AssignmentMechanicRepository;
import com.positivity.shopmanager.internal.repository.AssignmentRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.service.AssignmentServiceImpl;
import com.positivity.shopmanager.service.dto.AssignmentResponse;
import com.positivity.shopmanager.service.dto.CreateAssignmentRequest;
import com.positivity.shopmanager.service.dto.MechanicAssignmentItem;
import com.positivity.shopmanager.service.enums.AssignmentStatus;
import com.positivity.shopmanager.service.enums.MechanicRole;
import com.positivity.shopmanager.internal.entity.Assignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class AssignmentServiceTest {

        private static final Instant FIXED_NOW = Instant.parse("2025-06-01T10:00:00Z");
        private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

        @Mock
        private AppointmentRepository appointmentRepository;
        @Mock
        private MechanicRepository mechanicRepository;
        @Mock
        private AssignmentRepository assignmentRepository;
        @Mock
        private AssignmentMechanicRepository assignmentMechanicRepository;

        private AssignmentServiceImpl service;

        @BeforeEach
        void setUp() {
                service = new AssignmentServiceImpl(
                                appointmentRepository,
                                mechanicRepository,
                                assignmentRepository,
                                assignmentMechanicRepository,
                                FIXED_CLOCK);
        }

        // --- AC-4: role validation ---

        @Test
        void ac4_multipleWithNoLead_throwsIllegalArgument() {
                var request = CreateAssignmentRequest.builder()
                                .appointmentId(UUID.randomUUID())
                                .mechanics(List.of(
                                                MechanicAssignmentItem.builder().mechanicPersonId("P1")
                                                                .role(MechanicRole.ASSIST).build(),
                                                MechanicAssignmentItem.builder().mechanicPersonId("P2")
                                                                .role(MechanicRole.ASSIST).build()))
                                .build();

                assertThatThrownBy(() -> service.create(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("LEAD");

                verify(appointmentRepository, never()).findById(any());
                verify(assignmentRepository, never()).save(any());
        }

        // --- appointment must exist (hard block) ---

        @Test
        void appointmentNotFound_throwsIllegalArgument() {
                UUID appointmentId = UUID.randomUUID();
                var request = buildSingleLeadRequest(appointmentId, "P-001");

                when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> service.create(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("Appointment");

                verify(assignmentRepository, never()).save(any());
        }

        // --- AC-6: appointment must be SCHEDULED ---

        @Test
        void ac6_appointmentCancelled_throwsIllegalStateException() {
                UUID appointmentId = UUID.randomUUID();
                var appointment = buildAppointment(appointmentId, AppointmentStatus.CANCELLED);
                var request = buildSingleLeadRequest(appointmentId, "P-001");

                when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

                assertThatThrownBy(() -> service.create(request))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("SCHEDULED");

                verify(assignmentRepository, never()).save(any());
        }

        // --- AC-1: happy-path single LEAD mechanic ---

        @Test
        void ac7_mechanicNotFound_throwsIllegalArgument() {
                UUID appointmentId = UUID.randomUUID();
                var appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
                var request = buildSingleLeadRequest(appointmentId, "P-UNKNOWN");

                when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
                when(mechanicRepository.findByPersonId("P-UNKNOWN")).thenReturn(Optional.empty());

                assertThatThrownBy(() -> service.create(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("personId");

                verify(assignmentRepository, never()).save(any());
        }

        // --- AC-1: happy-path single LEAD mechanic ---

        @Test
        void ac1_singleLead_createsConfirmedAssignment() {
                UUID appointmentId = UUID.randomUUID();
                UUID mechanicId = UUID.randomUUID();
                var mechanic = buildMechanic(mechanicId, "P-001");
                var appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
                var savedAssignment = Assignment.builder()
                                .assignmentId(UUID.randomUUID())
                                .appointmentId(appointmentId)
                                .status(AssignmentStatusEnum.CONFIRMED)
                                .version(1)
                                .createdAt(FIXED_NOW)
                                .updatedAt(FIXED_NOW)
                                .build();
                var savedMechLink = AssignmentMechanic.builder()
                                .id(UUID.randomUUID())
                                .assignmentId(savedAssignment.getAssignmentId())
                                .mechanicId(mechanicId)
                                .role(MechanicRoleEnum.LEAD)
                                .build();

                var request = buildSingleLeadRequest(appointmentId, "P-001");

                when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
                when(mechanicRepository.findByPersonId("P-001")).thenReturn(Optional.of(mechanic));
                when(assignmentRepository.save(any())).thenReturn(savedAssignment);
                when(assignmentMechanicRepository.save(any())).thenReturn(savedMechLink);
                when(assignmentMechanicRepository.findByAssignmentId(savedAssignment.getAssignmentId()))
                                .thenReturn(List.of(savedMechLink));

                AssignmentResponse response = service.create(request);

                assertThat(response).isNotNull();
                assertThat(response.getStatus()).isEqualTo(AssignmentStatus.CONFIRMED);
                assertThat(response.getAppointmentId()).isEqualTo(appointmentId);
                assertThat(response.getMechanics()).hasSize(1);
                assertThat(response.getMechanics().get(0).getRole()).isEqualTo(MechanicRole.LEAD);
                assertThat(response.getMechanics().get(0).getMechanicId()).isEqualTo(mechanicId);
        }

        // --- AC-5: override field round-trip ---

        @Test
        void ac5_overrideFieldRoundTrips() {
                UUID appointmentId = UUID.randomUUID();
                UUID mechanicId = UUID.randomUUID();
                var mechanic = buildMechanic(mechanicId, "P-001");
                var appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
                var savedAssignment = Assignment.builder()
                                .assignmentId(UUID.randomUUID())
                                .appointmentId(appointmentId)
                                .status(AssignmentStatusEnum.CONFIRMED)
                                .isOverride(true)
                                .overrideReason("manager approved")
                                .version(1)
                                .createdAt(FIXED_NOW)
                                .updatedAt(FIXED_NOW)
                                .build();
                var savedMechLink = AssignmentMechanic.builder()
                                .id(UUID.randomUUID())
                                .assignmentId(savedAssignment.getAssignmentId())
                                .mechanicId(mechanicId)
                                .role(MechanicRoleEnum.LEAD)
                                .build();

                var request = CreateAssignmentRequest.builder()
                                .appointmentId(appointmentId)
                                .mechanics(List.of(
                                                MechanicAssignmentItem.builder().mechanicPersonId("P-001")
                                                                .role(MechanicRole.LEAD).build()))
                                .override(true)
                                .overrideReason("manager approved")
                                .build();

                when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
                when(mechanicRepository.findByPersonId("P-001")).thenReturn(Optional.of(mechanic));
                when(assignmentRepository.save(any())).thenReturn(savedAssignment);
                when(assignmentMechanicRepository.save(any())).thenReturn(savedMechLink);
                when(assignmentMechanicRepository.findByAssignmentId(savedAssignment.getAssignmentId()))
                                .thenReturn(List.of(savedMechLink));

                AssignmentResponse response = service.create(request);

                assertThat(response.isOverride()).isTrue();
        }

        // --- AC-2: get by appointmentId ---

        @Test
        void ac2_getByAppointmentId_returnsMappedList() {
                UUID appointmentId = UUID.randomUUID();
                UUID assignmentId = UUID.randomUUID();
                UUID mechanicId = UUID.randomUUID();
                var assignment = Assignment.builder()
                                .assignmentId(assignmentId)
                                .appointmentId(appointmentId)
                                .status(AssignmentStatusEnum.CONFIRMED)
                                .version(1)
                                .createdAt(FIXED_NOW)
                                .updatedAt(FIXED_NOW)
                                .build();
                var mechLink = AssignmentMechanic.builder()
                                .id(UUID.randomUUID())
                                .assignmentId(assignmentId)
                                .mechanicId(mechanicId)
                                .role(MechanicRoleEnum.LEAD)
                                .build();

                when(assignmentRepository.findByAppointmentId(appointmentId)).thenReturn(List.of(assignment));
                when(assignmentMechanicRepository.findByAssignmentId(assignmentId)).thenReturn(List.of(mechLink));

                List<AssignmentResponse> results = service.getByAppointmentId(appointmentId);

                assertThat(results).hasSize(1);
                assertThat(results.get(0).getStatus()).isEqualTo(AssignmentStatus.CONFIRMED);
                assertThat(results.get(0).getMechanics()).hasSize(1);
                assertThat(results.get(0).getMechanics().get(0).getMechanicId()).isEqualTo(mechanicId);
        }

        // --- helpers ---

        private static CreateAssignmentRequest buildSingleLeadRequest(UUID appointmentId, String personId) {
                return CreateAssignmentRequest.builder()
                                .appointmentId(appointmentId)
                                .mechanics(List.of(
                                                MechanicAssignmentItem.builder()
                                                                .mechanicPersonId(personId)
                                                                .role(MechanicRole.LEAD)
                                                                .build()))
                                .build();
        }

        private static Appointment buildAppointment(UUID appointmentId, AppointmentStatus status) {
                return Appointment.builder()
                                .appointmentId(appointmentId)
                                .crmCustomerId(UUID.randomUUID())
                                .crmVehicleId(UUID.randomUUID())
                                .locationId(UUID.randomUUID())
                                .startAt(FIXED_NOW)
                                .endAt(FIXED_NOW.plusSeconds(3600))
                                .status(status)
                                .build();
        }

        private static Mechanic buildMechanic(UUID mechanicId, String personId) {
                return Mechanic.builder()
                                .mechanicId(mechanicId)
                                .personId(personId)
                                .firstName("Test")
                                .lastName("Mechanic")
                                .build();
        }
}
