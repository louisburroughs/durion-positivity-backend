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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssignmentServiceImpl implements AssignmentService {

    private final AppointmentRepository appointmentRepository;
    private final MechanicRepository mechanicRepository;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentMechanicRepository assignmentMechanicRepository;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull AssignmentResponse create(@NonNull CreateAssignmentRequest request) {
        validateAtLeastOneLeadMechanic(request.getMechanics());

        var appointment = appointmentRepository.findById(request.getAppointmentId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Appointment not found: " + request.getAppointmentId()));

        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new IllegalStateException(
                    "Appointment must be SCHEDULED to create an assignment, current status: "
                    + appointment.getStatus());
        }

        Instant now = Instant.now(clock);

        // Resolve all mechanics first — fail fast before any persistence
        List<Mechanic> resolvedMechanics = new ArrayList<>(request.getMechanics().size());
        for (MechanicAssignmentItem item : request.getMechanics()) {
            var mechanic = mechanicRepository.findByPersonId(item.getMechanicPersonId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Mechanic not found for personId: " + item.getMechanicPersonId()));
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

        for (int i = 0; i < request.getMechanics().size(); i++) {
            MechanicAssignmentItem item = request.getMechanics().get(i);
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

    private static void validateAtLeastOneLeadMechanic(List<MechanicAssignmentItem> mechanics) {
        boolean hasLead = mechanics.stream().anyMatch(m -> m.getRole() == MechanicRole.LEAD);
        if (!hasLead) {
            throw new IllegalArgumentException(
                    "Assignment must include at least one mechanic with role LEAD");
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
                .createdAt(assignment.getCreatedAt())
                .build();
    }
}
