package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.entity.Assignment;
import com.positivity.shopmanager.internal.entity.AssignmentMechanic;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.AssignmentStatusEnum;
import com.positivity.shopmanager.internal.enums.MechanicRoleEnum;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AssignmentMechanicRepository;
import com.positivity.shopmanager.internal.repository.AssignmentRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.service.AssignmentService;
import com.positivity.shopmanager.service.dto.AssignedMechanicInfo;
import com.positivity.shopmanager.service.dto.AssignmentResponse;
import com.positivity.shopmanager.service.dto.CreateAssignmentRequest;
import com.positivity.shopmanager.service.dto.MechanicAssignmentItem;
import com.positivity.shopmanager.service.enums.AssignmentStatus;
import com.positivity.shopmanager.service.enums.MechanicRole;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssignmentServiceImpl implements AssignmentService {

        private static final String ASSIGNMENT_OVERRIDE_AUTHORITY = "shop:schedule:edit";

        private final AppointmentRepository appointmentRepository;
        private final MechanicRepository mechanicRepository;
        private final AssignmentRepository assignmentRepository;
        private final AssignmentMechanicRepository assignmentMechanicRepository;
        private final Clock clock;

        @Override
        @Transactional
        public @NonNull AssignmentResponse create(@NonNull CreateAssignmentRequest request) {
                // Resolve effective roles (single mechanic with null role defaults to LEAD) —
                // F-06
                List<MechanicAssignmentItem> mechanics = resolveEffectiveRoles(request.getMechanics());
                validateLeadConstraint(mechanics);

                // F-10: override requires additional authority
                if (request.isOverride()) {
                        var auth = SecurityContextHolder.getContext().getAuthentication();
                        boolean canOverride = auth != null && auth.getAuthorities().stream()
                                        .anyMatch(a -> a.getAuthority().equals(ASSIGNMENT_OVERRIDE_AUTHORITY));
                        if (!canOverride) {
                                throw new AccessDeniedException(
                                                "Overriding assignment constraints requires authority '"
                                                                + ASSIGNMENT_OVERRIDE_AUTHORITY + "'");
                        }
                        if (request.getOverrideReason() == null || request.getOverrideReason().isBlank()) {
                                throw new IllegalArgumentException(
                                                "overrideReason must not be blank when override=true");
                        }
                }

                var appointment = appointmentRepository.findById(request.getAppointmentId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "Appointment not found: " + request.getAppointmentId()));

                if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
                        throw new IllegalStateException(
                                        "Appointment must be SCHEDULED to create an assignment, current status: "
                                                        + appointment.getStatus());
                }

                List<AssignmentStatusEnum> activeStatuses = List.of(
                                AssignmentStatusEnum.CONFIRMED,
                                AssignmentStatusEnum.IN_PROGRESS);
                assignmentRepository
                                .findByAppointmentIdAndStatusIn(request.getAppointmentId(), activeStatuses)
                                .ifPresent(existing -> {
                                        throw new IllegalStateException(
                                                        "An active assignment already exists for appointment: "
                                                                        + request.getAppointmentId());
                                });

                Instant now = Instant.now(clock);

                // Resolve all mechanics first — fail fast before any persistence
                List<Mechanic> resolvedMechanics = new ArrayList<>(mechanics.size());
                for (MechanicAssignmentItem item : mechanics) {
                        var mechanic = mechanicRepository.findByPersonId(item.getMechanicPersonId())
                                        .orElseThrow(() -> new IllegalArgumentException(
                                                        "Mechanic not found for personId: "
                                                                        + item.getMechanicPersonId()));
                        resolvedMechanics.add(mechanic);
                }

                Assignment assignment = assignmentRepository.save(Assignment.builder()
                                .appointmentId(request.getAppointmentId())
                                .status(AssignmentStatusEnum.CONFIRMED)
                                .resourceId(request.getResourceId())
                                .resourceType(request.getResourceType())
                                .isOverride(request.isOverride())
                                .overrideReason(request.getOverrideReason())
                                .version(1)
                                .createdAt(now)
                                .updatedAt(now)
                                .build());

                for (int i = 0; i < mechanics.size(); i++) {
                        MechanicAssignmentItem item = mechanics.get(i);
                        assignmentMechanicRepository.save(AssignmentMechanic.builder()
                                        .assignmentId(assignment.getAssignmentId())
                                        .mechanicId(resolvedMechanics.get(i).getMechanicId())
                                        .role(MechanicRoleEnum.valueOf(item.getRole().name()))
                                        .build());
                }

                var mechLinks = assignmentMechanicRepository.findByAssignmentId(assignment.getAssignmentId());
                return mapToResponse(assignment, mechLinks);
        }

        @Override
        @Transactional(readOnly = true)
        public @NonNull List<AssignmentResponse> getByAppointmentId(@NonNull UUID appointmentId) {
                var assignments = assignmentRepository.findByAppointmentId(appointmentId);
                List<AssignmentResponse> results = new ArrayList<>();
                for (var assignment : assignments) {
                        var mechLinks = assignmentMechanicRepository.findByAssignmentId(assignment.getAssignmentId());
                        results.add(mapToResponse(assignment, mechLinks));
                }
                return results;
        }

        /** F-06: Default null role to LEAD for single-mechanic assignments. */
        private static List<MechanicAssignmentItem> resolveEffectiveRoles(List<MechanicAssignmentItem> mechanics) {
                if (mechanics.size() == 1 && mechanics.get(0).getRole() == null) {
                        return List.of(MechanicAssignmentItem.builder()
                                        .mechanicPersonId(mechanics.get(0).getMechanicPersonId())
                                        .role(MechanicRole.LEAD)
                                        .build());
                }
                return mechanics;
        }

        /** F-09: Enforce exactly one LEAD mechanic for multi-mechanic assignments. */
        private static void validateLeadConstraint(List<MechanicAssignmentItem> mechanics) {
                if (mechanics.size() > 1 && mechanics.stream().anyMatch(m -> m.getRole() == null)) {
                        throw new IllegalArgumentException(
                                        "All mechanics in a multi-mechanic assignment must have an explicit role");
                }

                long leadCount = mechanics.stream()
                                .filter(m -> m.getRole() == MechanicRole.LEAD)
                                .count();
                if (leadCount == 0) {
                        throw new IllegalArgumentException(
                                        "Assignment must include exactly one mechanic with role LEAD");
                }
                if (mechanics.size() > 1 && leadCount > 1) {
                        throw new IllegalArgumentException(
                                        "Multi-mechanic assignment must have exactly one LEAD; found " + leadCount);
                }
        }

        private static AssignmentResponse mapToResponse(
                        Assignment assignment, List<AssignmentMechanic> mechLinks) {
                List<AssignedMechanicInfo> mechanicInfos = mechLinks.stream()
                                .map(link -> AssignedMechanicInfo.builder()
                                                .mechanicId(link.getMechanicId())
                                                .role(MechanicRole.valueOf(link.getRole().name()))
                                                .build())
                                .toList();
                return AssignmentResponse.builder()
                                .assignmentId(assignment.getAssignmentId())
                                .appointmentId(assignment.getAppointmentId())
                                .mechanics(mechanicInfos)
                                .resourceId(assignment.getResourceId())
                                .resourceType(assignment.getResourceType())
                                .status(AssignmentStatus.valueOf(assignment.getStatus().name()))
                                .override(assignment.isOverride())
                                .assignmentNotes(assignment.getNotes())
                                .assignedAt(assignment.getCreatedAt())
                                .lastUpdatedAt(assignment.getUpdatedAt())
                                .build();
        }
}
