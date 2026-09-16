package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
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
import com.positivity.shopmanager.internal.service.dto.AssignedMechanicInfo;
import com.positivity.shopmanager.internal.service.dto.AssignmentResponse;
import com.positivity.shopmanager.internal.service.dto.CreateAssignmentRequest;
import com.positivity.shopmanager.internal.service.dto.MechanicAssignmentItem;
import com.positivity.shopmanager.internal.service.enums.MechanicRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentServiceImpl implements AssignmentService {

    static final String ASSIGNMENT_OVERRIDE_AUTHORITY = "shop:schedule:edit";

    private final AppointmentRepository appointmentRepository;
    private final MechanicRepository mechanicRepository;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentMechanicRepository assignmentMechanicRepository;
    private final AppointmentServiceRequestRepository appointmentServiceRequestRepository;
    private final ExtLocationReplicaRepository extLocationReplicaRepository;
    private final LocationHoursParser locationHoursParser;
    private final SkillRequirementResolver skillRequirementResolver;
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
            boolean canOverride = auth != null
                    && auth.getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals(ASSIGNMENT_OVERRIDE_AUTHORITY));
            if (!canOverride) {
                throw new AccessDeniedException(
                        "Overriding assignment constraints requires authority '" + ASSIGNMENT_OVERRIDE_AUTHORITY + "'");
            }
            if (request.getOverrideReason() == null
                    || request.getOverrideReason().isBlank()) {
                throw new ShopManagerValidationException("overrideReason must not be blank when override=true");
            }
        }

        var appointment = appointmentRepository
                .findById(request.getAppointmentId())
                .orElseThrow(() -> new AppointmentNotFoundException(request.getAppointmentId()));

        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new IllegalStateException("Appointment must be SCHEDULED to create an assignment, current status: "
                    + appointment.getStatus());
        }

        assignmentRepository
                .findByAppointment_AppointmentIdAndStatusIn(request.getAppointmentId(), AssignmentStatusEnum.active())
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            "An active assignment already exists for appointment: " + request.getAppointmentId());
                });

        Instant now = Instant.now(clock);

        // Resolve all mechanics first — fail fast before any persistence
        List<Mechanic> resolvedMechanics = new ArrayList<>(mechanics.size());
        for (MechanicAssignmentItem item : mechanics) {
            var mechanic = mechanicRepository
                    .findByPersonId(parsePersonId(item.getMechanicPersonId()))
                    .orElseThrow(() -> new ShopManagerValidationException(
                            "Mechanic not found for personId: " + item.getMechanicPersonId()));
            resolvedMechanics.add(mechanic);
        }

        // Spec D10 (c), CAP-329: assignment is where competence has a consequence. A booking only
        // warns, but an assignment with no competent candidate cannot complete — it is parked in
        // AWAITING_SKILL_FULFILLMENT (DECISION-SHOPMGMT-010) until a holder is assigned or a manager
        // overrides. Zero requirements (none configured, or none for this vehicle class) parks nothing.
        List<String> unmet = unmetSkills(appointment, resolvedMechanics);
        AssignmentStatusEnum status = unmet.isEmpty() || request.isOverride()
                ? AssignmentStatusEnum.ASSIGNED
                : AssignmentStatusEnum.AWAITING_SKILL_FULFILLMENT;
        if (!unmet.isEmpty()) {
            log.info(
                    "Assignment for appointment {} {}: no assigned mechanic holds {}",
                    appointment.getAppointmentId(),
                    request.isOverride() ? "overridden to ASSIGNED" : "parked AWAITING_SKILL_FULFILLMENT",
                    unmet);
        }

        Assignment assignment = assignmentRepository.save(Assignment.builder()
                .appointment(appointment)
                .status(status)
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
                    .assignment(assignment)
                    .mechanic(resolvedMechanics.get(i))
                    .role(MechanicRoleEnum.valueOf(item.getRole().name()))
                    .build());
        }

        var mechLinks = assignmentMechanicRepository.findByAssignment_AssignmentId(assignment.getAssignmentId());
        return mapToResponse(assignment, mechLinks);
    }

    /**
     * Required skill codes (for the appointment's services and vehicle class) that none of the
     * assigned mechanics holds on the appointment's facility-local date. Empty when nothing is
     * required, or somebody assigned holds all of it. Read through {@link SkillRequirementResolver},
     * the same reading the conflict evaluator and the opening search use.
     */
    private List<String> unmetSkills(Appointment appointment, List<Mechanic> mechanics) {
        List<UUID> serviceIds =
                appointmentServiceRequestRepository
                        .findByAppointment_AppointmentId(appointment.getAppointmentId())
                        .stream()
                        .map(AppointmentServiceRequest::getServiceEntityId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (serviceIds.isEmpty()) {
            return List.of();
        }
        Map<String, UUID> required = skillRequirementResolver.requiredSkills(
                serviceIds, skillRequirementResolver.gvwrClassOf(appointment.getCrmVehicleId()));
        if (required.isEmpty()) {
            return List.of();
        }
        ZoneId zone = extLocationReplicaRepository
                .findById(appointment.getLocationId())
                .map(location -> locationHoursParser.parseZone(location.getLocationId(), location.getTimezone()))
                .orElse(null);
        LocalDate localDate = appointment
                .getStartAt()
                .atZone(zone == null ? ZoneOffset.UTC : zone)
                .toLocalDate();
        List<UUID> persons = mechanics.stream().map(Mechanic::getPersonId).toList();
        Map<String, Set<UUID>> holders = skillRequirementResolver.holdersBySkill(persons, required.keySet(), localDate);
        // Competence is per person: one mechanic must hold everything the work needs.
        boolean anyoneHoldsAll = persons.stream()
                .anyMatch(person -> required.keySet().stream()
                        .allMatch(code -> holders.getOrDefault(code, Set.of()).contains(person)));
        return anyoneHoldsAll ? List.of() : SkillRequirementResolver.missingFor(required.keySet(), holders, persons);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<AssignmentResponse> getByAppointmentId(@NonNull UUID appointmentId) {
        var assignments = assignmentRepository.findByAppointment_AppointmentId(appointmentId);
        List<AssignmentResponse> results = new ArrayList<>();
        for (var assignment : assignments) {
            var mechLinks = assignmentMechanicRepository.findByAssignment_AssignmentId(assignment.getAssignmentId());
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
            throw new ShopManagerValidationException(
                    "All mechanics in a multi-mechanic assignment must have an explicit role");
        }

        long leadCount =
                mechanics.stream().filter(m -> m.getRole() == MechanicRole.LEAD).count();
        if (leadCount == 0) {
            throw new ShopManagerValidationException("Assignment must include exactly one mechanic with role LEAD");
        }
        if (mechanics.size() > 1 && leadCount > 1) {
            throw new ShopManagerValidationException(
                    "Multi-mechanic assignment must have exactly one LEAD; found " + leadCount);
        }
    }

    private static AssignmentResponse mapToResponse(Assignment assignment, List<AssignmentMechanic> mechLinks) {
        List<AssignedMechanicInfo> mechanicInfos = mechLinks.stream()
                .map(link -> AssignedMechanicInfo.builder()
                        .mechanicId(link.getMechanicId())
                        .role(MechanicRole.valueOf(link.getRole().name()))
                        .build())
                .toList();
        return AssignmentResponse.builder()
                .assignmentId(assignment.getAssignmentId())
                .appointmentId(assignment.getAppointment().getAppointmentId())
                .mechanics(mechanicInfos)
                .resourceId(assignment.getResourceId())
                .resourceType(assignment.getResourceType())
                .status(assignment.getStatus())
                .override(assignment.isOverride())
                .assignmentNotes(assignment.getNotes())
                .assignedAt(assignment.getCreatedAt())
                .lastUpdatedAt(assignment.getUpdatedAt())
                .build();
    }

    /** Person ids are the People domain's UUIDs (CAP-328); anything else names nobody. */
    private static UUID parsePersonId(String personId) {
        try {
            return UUID.fromString(personId == null ? "" : personId.trim());
        } catch (IllegalArgumentException notAUuid) {
            throw new ShopManagerValidationException("mechanicPersonId is not a UUID: " + personId);
        }
    }
}
