package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.Assignment;
import com.positivity.shopmanager.internal.entity.AssignmentMechanic;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.AssignmentStatusEnum;
import com.positivity.shopmanager.internal.enums.MechanicRoleEnum;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.AssignmentMechanicRepository;
import com.positivity.shopmanager.internal.repository.AssignmentRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.service.dto.AssignmentResponse;
import com.positivity.shopmanager.internal.service.dto.CreateAssignmentRequest;
import com.positivity.shopmanager.internal.service.dto.MechanicAssignmentItem;
import com.positivity.shopmanager.internal.service.enums.MechanicRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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

    @Mock
    private AppointmentServiceRequestRepository appointmentServiceRequestRepository;

    @Mock
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Mock
    private SkillRequirementResolver skillRequirementResolver;

    private AssignmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AssignmentServiceImpl(
                appointmentRepository,
                mechanicRepository,
                assignmentRepository,
                assignmentMechanicRepository,
                appointmentServiceRequestRepository,
                extLocationReplicaRepository,
                new LocationHoursParser(new com.fasterxml.jackson.databind.ObjectMapper()),
                skillRequirementResolver,
                FIXED_CLOCK);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- AC-4: role validation ---

    @Test
    void ac4_multipleWithNoLead_throwsValidationException() {
        var request = CreateAssignmentRequest.builder()
                .appointmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .mechanics(List.of(
                        MechanicAssignmentItem.builder()
                                .mechanicPersonId("01960011-0000-7000-8000-000000000001")
                                .role(MechanicRole.ASSIST)
                                .build(),
                        MechanicAssignmentItem.builder()
                                .mechanicPersonId("01960011-0000-7000-8000-000000000002")
                                .role(MechanicRole.ASSIST)
                                .build()))
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining("LEAD");

        verify(appointmentRepository, never()).findById(any());
        verify(assignmentRepository, never()).save(any());
    }

    // --- C2-F-02: multi-mechanic with null role throws ---

    @Test
    void c2_f02_multiMechanicWithNullRole_throwsValidationException() {
        var request = CreateAssignmentRequest.builder()
                .appointmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .mechanics(List.of(
                        MechanicAssignmentItem.builder()
                                .mechanicPersonId("01960011-0000-7000-8000-000000000001")
                                .role(MechanicRole.LEAD)
                                .build(),
                        MechanicAssignmentItem.builder()
                                .mechanicPersonId("01960011-0000-7000-8000-000000000002")
                                .role(null)
                                .build()))
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining("explicit role");

        verify(appointmentRepository, never()).findById(any());
        verify(assignmentRepository, never()).save(any());
    }

    // --- appointment must exist (hard block) ---

    @Test
    void appointmentNotFound_throwsAppointmentNotFoundException() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var request = buildSingleLeadRequest(appointmentId, "01960011-0000-7000-8000-000000000101");

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(AppointmentNotFoundException.class)
                .hasMessageContaining("Appointment");

        verify(assignmentRepository, never()).save(any());
    }

    // --- AC-6: appointment must be SCHEDULED ---

    @Test
    void ac6_appointmentCancelled_throwsIllegalStateException() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var appointment = buildAppointment(appointmentId, AppointmentStatus.CANCELLED);
        var request = buildSingleLeadRequest(appointmentId, "01960011-0000-7000-8000-000000000101");

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SCHEDULED");

        verify(assignmentRepository, never()).save(any());
    }

    // --- C2-F-03: duplicate active assignment throws ---

    @Test
    void c2_f03_duplicateActiveAssignment_throwsIllegalState() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        var existingAssignment = buildSavedAssignment(appointmentId);
        var request = buildSingleLeadRequest(appointmentId, "01960011-0000-7000-8000-000000000101");

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(assignmentRepository.findByAppointment_AppointmentIdAndStatusIn(
                        appointmentId, AssignmentStatusEnum.active()))
                .thenReturn(Optional.of(existingAssignment));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active assignment already exists");

        verify(assignmentRepository, never()).save(any());
    }

    // --- mechanicPersonId must be a UUID ---

    @Test
    void mechanicPersonIdNotAUuid_throwsValidationExceptionAndSavesNothing() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        var request = buildSingleLeadRequest(appointmentId, "not-a-uuid");

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining("not a UUID");

        verify(mechanicRepository, never()).findByPersonId(any());
        verify(assignmentRepository, never()).save(any());
    }

    // --- AC-1: happy-path single LEAD mechanic ---

    @Test
    void ac7_mechanicNotFound_throwsValidationException() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        var request = buildSingleLeadRequest(appointmentId, "01960011-0000-7000-8000-0000000000ff");

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(mechanicRepository.findByPersonId(UUID.fromString("01960011-0000-7000-8000-0000000000ff")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining("personId");

        verify(assignmentRepository, never()).save(any());
    }

    // --- AC-1: happy-path single LEAD mechanic ---

    @Test
    void ac1_singleLead_createsConfirmedAssignment() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID mechanicId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var mechanic = buildMechanic(mechanicId, "01960011-0000-7000-8000-000000000101");
        var appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        var savedAssignment = Assignment.builder()
                .assignmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .appointment(appointment)
                .status(AssignmentStatusEnum.ASSIGNED)
                .version(1)
                .createdAt(FIXED_NOW)
                .updatedAt(FIXED_NOW)
                .build();
        var savedMechLink = AssignmentMechanic.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .assignment(savedAssignment)
                .mechanic(Mechanic.builder().mechanicId(mechanicId).build())
                .role(MechanicRoleEnum.LEAD)
                .build();

        var request = buildSingleLeadRequest(appointmentId, "01960011-0000-7000-8000-000000000101");

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(mechanicRepository.findByPersonId(UUID.fromString("01960011-0000-7000-8000-000000000101")))
                .thenReturn(Optional.of(mechanic));
        when(assignmentRepository.save(any())).thenReturn(savedAssignment);
        when(assignmentMechanicRepository.save(any())).thenReturn(savedMechLink);
        when(assignmentMechanicRepository.findByAssignment_AssignmentId(savedAssignment.getAssignmentId()))
                .thenReturn(List.of(savedMechLink));

        AssignmentResponse response = service.create(request);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(AssignmentStatusEnum.ASSIGNED);
        assertThat(response.getAppointmentId()).isEqualTo(appointmentId);
        assertThat(response.getMechanics()).hasSize(1);
        assertThat(response.getMechanics().get(0).getRole()).isEqualTo(MechanicRole.LEAD);
        assertThat(response.getMechanics().get(0).getMechanicId()).isEqualTo(mechanicId);
    }

    // --- AC-5: override field round-trip ---

    @Test
    void ac5_overrideFieldRoundTrips() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID mechanicId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var mechanic = buildMechanic(mechanicId, "01960011-0000-7000-8000-000000000101");
        var appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        var savedAssignment = Assignment.builder()
                .assignmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .appointment(appointment)
                .status(AssignmentStatusEnum.ASSIGNED)
                .isOverride(true)
                .overrideReason("manager approved")
                .version(1)
                .createdAt(FIXED_NOW)
                .updatedAt(FIXED_NOW)
                .build();
        var savedMechLink = AssignmentMechanic.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .assignment(savedAssignment)
                .mechanic(Mechanic.builder().mechanicId(mechanicId).build())
                .role(MechanicRoleEnum.LEAD)
                .build();

        var request = CreateAssignmentRequest.builder()
                .appointmentId(appointmentId)
                .mechanics(List.of(MechanicAssignmentItem.builder()
                        .mechanicPersonId("01960011-0000-7000-8000-000000000101")
                        .role(MechanicRole.LEAD)
                        .build()))
                .override(true)
                .overrideReason("manager approved")
                .build();

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(mechanicRepository.findByPersonId(UUID.fromString("01960011-0000-7000-8000-000000000101")))
                .thenReturn(Optional.of(mechanic));
        when(assignmentRepository.save(any())).thenReturn(savedAssignment);
        when(assignmentMechanicRepository.save(any())).thenReturn(savedMechLink);
        when(assignmentMechanicRepository.findByAssignment_AssignmentId(savedAssignment.getAssignmentId()))
                .thenReturn(List.of(savedMechLink));

        var overrideAuth = new UsernamePasswordAuthenticationToken(
                "manager", null, List.of(new SimpleGrantedAuthority("shop:schedule:edit")));
        SecurityContextHolder.getContext().setAuthentication(overrideAuth);

        AssignmentResponse response = service.create(request);

        assertThat(response.isOverride()).isTrue();
    }

    // --- AC-2: get by appointmentId ---

    @Test
    void ac2_getByAppointmentId_returnsMappedList() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID assignmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID mechanicId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var assignment = Assignment.builder()
                .assignmentId(assignmentId)
                .appointment(buildAppointment(appointmentId, AppointmentStatus.SCHEDULED))
                .status(AssignmentStatusEnum.ASSIGNED)
                .version(1)
                .createdAt(FIXED_NOW)
                .updatedAt(FIXED_NOW)
                .build();
        var mechLink = AssignmentMechanic.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .assignment(assignment)
                .mechanic(Mechanic.builder().mechanicId(mechanicId).build())
                .role(MechanicRoleEnum.LEAD)
                .build();

        when(assignmentRepository.findByAppointment_AppointmentId(appointmentId))
                .thenReturn(List.of(assignment));
        when(assignmentMechanicRepository.findByAssignment_AssignmentId(assignmentId))
                .thenReturn(List.of(mechLink));

        List<AssignmentResponse> results = service.getByAppointmentId(appointmentId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo(AssignmentStatusEnum.ASSIGNED);
        assertThat(results.get(0).getMechanics()).hasSize(1);
        assertThat(results.get(0).getMechanics().get(0).getMechanicId()).isEqualTo(mechanicId);
    }

    // --- helpers ---

    // --- Spec D10 (c), CAP-329: competence has a consequence at assignment ---

    @org.junit.jupiter.api.Nested
    class SkillFulfillment {
        private final UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        private final UUID mechanicId = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        private final UUID personId = UUID.fromString("01960011-0000-7000-8000-0000000000e1");
        private final UUID brakeJob = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a535");
        private final UUID brakesHeavy = UUID.fromString("01960011-0000-7000-8000-000000000041");

        @org.junit.jupiter.api.BeforeEach
        void appointmentWithABrakeJob() {
            Appointment appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
            when(assignmentRepository.findByAppointment_AppointmentIdAndStatusIn(eq(appointmentId), any()))
                    .thenReturn(Optional.empty());
            when(mechanicRepository.findByPersonId(personId))
                    .thenReturn(Optional.of(buildMechanic(mechanicId, personId.toString())));
            when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(appointmentId))
                    .thenReturn(List.of(com.positivity.shopmanager.internal.entity.AppointmentServiceRequest.builder()
                            .serviceEntityId(brakeJob)
                            .build()));
            // lenient: the no-service-requests case never reaches the resolver, by design.
            org.mockito.Mockito.lenient()
                    .when(skillRequirementResolver.requiredSkills(eq(List.of(brakeJob)), any()))
                    .thenReturn(Map.of("BRAKES-MEDIUM_HEAVY", brakesHeavy));
            when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        void mechanicHoldingEveryRequiredSkillIsAssigned() {
            when(skillRequirementResolver.holdersBySkill(eq(List.of(personId)), any(), any()))
                    .thenReturn(Map.of("BRAKES-MEDIUM_HEAVY", Set.of(personId)));

            AssignmentResponse response = service.create(buildSingleLeadRequest(appointmentId, personId.toString()));

            assertThat(response.getStatus()).isEqualTo(AssignmentStatusEnum.ASSIGNED);
        }

        @Test
        void mechanicLackingARequiredSkillIsParkedAwaitingSkillFulfillment() {
            when(skillRequirementResolver.holdersBySkill(eq(List.of(personId)), any(), any()))
                    .thenReturn(Map.of());

            AssignmentResponse response = service.create(buildSingleLeadRequest(appointmentId, personId.toString()));

            assertThat(response.getStatus()).isEqualTo(AssignmentStatusEnum.AWAITING_SKILL_FULFILLMENT);
        }

        @Test
        void anAuthorisedOverrideAssignsDespiteTheGap() {
            when(skillRequirementResolver.holdersBySkill(eq(List.of(personId)), any(), any()))
                    .thenReturn(Map.of());
            SecurityContextHolder.getContext()
                    .setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(
                            "manager", "n/a", AssignmentServiceImpl.ASSIGNMENT_OVERRIDE_AUTHORITY));
            CreateAssignmentRequest request = CreateAssignmentRequest.builder()
                    .appointmentId(appointmentId)
                    .mechanics(List.of(MechanicAssignmentItem.builder()
                            .mechanicPersonId(personId.toString())
                            .role(MechanicRole.LEAD)
                            .build()))
                    .override(true)
                    .overrideReason("Truck brake job; Sam has done these under supervision")
                    .build();

            AssignmentResponse response = service.create(request);

            assertThat(response.getStatus()).isEqualTo(AssignmentStatusEnum.ASSIGNED);
        }

        @Test
        void anAppointmentWithoutServiceRequestsRequiresNothing() {
            when(appointmentServiceRequestRepository.findByAppointment_AppointmentId(appointmentId))
                    .thenReturn(List.of());

            AssignmentResponse response = service.create(buildSingleLeadRequest(appointmentId, personId.toString()));

            assertThat(response.getStatus()).isEqualTo(AssignmentStatusEnum.ASSIGNED);
            verify(skillRequirementResolver, never()).requiredSkills(any(), any());
        }
    }

    private static CreateAssignmentRequest buildSingleLeadRequest(UUID appointmentId, String personId) {
        return CreateAssignmentRequest.builder()
                .appointmentId(appointmentId)
                .mechanics(List.of(MechanicAssignmentItem.builder()
                        .mechanicPersonId(personId)
                        .role(MechanicRole.LEAD)
                        .build()))
                .build();
    }

    private static Appointment buildAppointment(UUID appointmentId, AppointmentStatus status) {
        return Appointment.builder()
                .appointmentId(appointmentId)
                .crmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .crmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .startAt(FIXED_NOW)
                .endAt(FIXED_NOW.plusSeconds(3600))
                .status(status)
                .build();
    }

    private static Mechanic buildMechanic(UUID mechanicId, String personId) {
        return Mechanic.builder()
                .mechanicId(mechanicId)
                .personId(UUID.fromString(personId))
                .firstName("Test")
                .lastName("Mechanic")
                .build();
    }

    private static Assignment buildSavedAssignment(UUID appointmentId) {
        return Assignment.builder()
                .assignmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .appointment(buildAppointment(appointmentId, AppointmentStatus.SCHEDULED))
                .status(AssignmentStatusEnum.ASSIGNED)
                .version(1)
                .createdAt(FIXED_NOW)
                .updatedAt(FIXED_NOW)
                .build();
    }

    private static AssignmentMechanic buildMechLink(UUID assignmentId, UUID mechanicId, MechanicRoleEnum role) {
        Assignment assignment = Assignment.builder()
                .assignmentId(assignmentId)
                .appointment(buildAppointment(
                        UUID.fromString("00000000-0000-0000-0000-000000000010"), AppointmentStatus.SCHEDULED))
                .status(AssignmentStatusEnum.ASSIGNED)
                .version(1)
                .createdAt(FIXED_NOW)
                .updatedAt(FIXED_NOW)
                .build();
        return AssignmentMechanic.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .assignment(assignment)
                .mechanic(Mechanic.builder().mechanicId(mechanicId).build())
                .role(role)
                .build();
    }

    // --- F-06: single mechanic with null role defaults to LEAD ---

    @Test
    void ac4_singleMechanicNullRole_defaultsToLead() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID mechanicId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var request = CreateAssignmentRequest.builder()
                .appointmentId(appointmentId)
                .mechanics(List.of(MechanicAssignmentItem.builder()
                        .mechanicPersonId("01960011-0000-7000-8000-000000000101")
                        .role(null)
                        .build()))
                .build();

        var appointment = buildAppointment(appointmentId, AppointmentStatus.SCHEDULED);
        var mechanic = buildMechanic(mechanicId, "01960011-0000-7000-8000-000000000101");
        var savedAssignment = buildSavedAssignment(appointmentId);
        var savedLink = buildMechLink(savedAssignment.getAssignmentId(), mechanicId, MechanicRoleEnum.LEAD);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(mechanicRepository.findByPersonId(UUID.fromString("01960011-0000-7000-8000-000000000101")))
                .thenReturn(Optional.of(mechanic));
        when(assignmentRepository.save(any())).thenReturn(savedAssignment);
        when(assignmentMechanicRepository.save(any())).thenReturn(savedLink);
        when(assignmentMechanicRepository.findByAssignment_AssignmentId(savedAssignment.getAssignmentId()))
                .thenReturn(List.of(savedLink));

        var response = service.create(request);

        assertThat(response.getMechanics()).hasSize(1);
        assertThat(response.getMechanics().get(0).getRole()).isEqualTo(MechanicRole.LEAD);
        verify(assignmentRepository).save(any());
    }

    // --- F-09: multiple mechanics with more than one LEAD throws ---

    @Test
    void ac4_multipleWithMultipleLeads_throwsValidationException() {
        var request = CreateAssignmentRequest.builder()
                .appointmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .mechanics(List.of(
                        MechanicAssignmentItem.builder()
                                .mechanicPersonId("01960011-0000-7000-8000-000000000001")
                                .role(MechanicRole.LEAD)
                                .build(),
                        MechanicAssignmentItem.builder()
                                .mechanicPersonId("01960011-0000-7000-8000-000000000002")
                                .role(MechanicRole.LEAD)
                                .build()))
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining("exactly one LEAD");

        verify(appointmentRepository, never()).findById(any());
        verify(assignmentRepository, never()).save(any());
    }

    // --- F-10: override permission check ---

    @Test
    void ac10_overrideWithoutPermission_throwsAccessDenied() {
        // SecurityContextHolder is empty in tests — canOverride=false
        var request = CreateAssignmentRequest.builder()
                .appointmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .mechanics(List.of(MechanicAssignmentItem.builder()
                        .mechanicPersonId("01960011-0000-7000-8000-000000000101")
                        .role(MechanicRole.LEAD)
                        .build()))
                .override(true)
                .overrideReason("approved")
                .build();

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(AccessDeniedException.class);

        verify(appointmentRepository, never()).findById(any());
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void ac10_overrideWithPermissionAndBlankReason_throwsValidationException() {
        var auth = new UsernamePasswordAuthenticationToken(
                "manager", null, List.of(new SimpleGrantedAuthority("shop:schedule:edit")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        var request = CreateAssignmentRequest.builder()
                .appointmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .mechanics(List.of(MechanicAssignmentItem.builder()
                        .mechanicPersonId("01960011-0000-7000-8000-000000000101")
                        .role(MechanicRole.LEAD)
                        .build()))
                .override(true)
                .overrideReason("  ")
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining("overrideReason");

        verify(appointmentRepository, never()).findById(any());
        verify(assignmentRepository, never()).save(any());
    }
}
