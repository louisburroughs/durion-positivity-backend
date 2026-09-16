package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.entity.ExtCatalogServiceReplica;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceSkillReplica;
import com.positivity.shopmanager.internal.entity.ExtPersonCredentialReplica;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceSkillReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonCredentialReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtVehicleReplicaRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The one reading of "which skills does this work need, and who here holds them" (CAP-329, spec
 * D8/D10/D13), shared by the submit-time {@link SchedulingConflictEvaluator} and the advisory
 * {@code GET /v1/schedules/openings} search so the two never disagree about competence.
 *
 * <ul>
 *   <li>(services, vehicle GVWR class) → required skill codes, from the catalog replica. A service
 *       whose requirements were never configured contributes nothing — "not configured" is not
 *       "requires nothing" (D4). A class-ranged requirement applies only when the class is known and
 *       inside the range; an unranged one (ANY) always applies.
 *   <li>(people, codes, date) → holders, from the credential replica. A credential counts only
 *       while held on the facility-local date (DECISION-SHOPMGMT-015; expiry inclusive,
 *       REVOKED/SUPERSEDED never), matching the Durion skill code or the issuer's own code,
 *       uppercase-and-trimmed on both sides (#2022 AC9).
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SkillRequirementResolver {

    static final String TECHNICIAN_ROLE = "TECHNICIAN";
    static final String STAFFING_ACTIVE = "ACTIVE";

    private final ExtCatalogServiceReplicaRepository catalogServiceRepository;
    private final ExtCatalogServiceSkillReplicaRepository catalogServiceSkillRepository;
    private final ExtPersonCredentialReplicaRepository credentialRepository;
    private final ExtVehicleReplicaRepository vehicleRepository;

    /** The vehicle's operator-set GVWR class (CAP-327), or null when unknown or undetermined. */
    public @Nullable Integer gvwrClassOf(@Nullable UUID vehicleId) {
        if (vehicleId == null) {
            return null;
        }
        return vehicleRepository
                .findById(vehicleId)
                .map(vehicle -> vehicle.getGvwrClass())
                .orElse(null);
    }

    /** The catalog services among {@code serviceIds} whose requirements have been configured. */
    public @NonNull Set<UUID> configuredServices(@NonNull Collection<UUID> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Set.of();
        }
        return catalogServiceRepository.findAllByServiceIdIn(serviceIds).stream()
                .filter(ExtCatalogServiceReplica::isRequirementsConfigured)
                .map(ExtCatalogServiceReplica::getServiceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Required skill codes for the class, insertion-ordered by code → skill id. Empty when nothing
     * is configured or nothing applies to the class.
     */
    public @NonNull Map<String, UUID> requiredSkills(
            @NonNull Collection<UUID> serviceIds, @Nullable Integer gvwrClass) {
        return requiredSkillsOf(configuredServices(serviceIds), gvwrClass);
    }

    /** As {@link #requiredSkills}, for services already known to be configured. */
    public @NonNull Map<String, UUID> requiredSkillsOf(@NonNull Set<UUID> configured, @Nullable Integer gvwrClass) {
        if (configured.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> required = new LinkedHashMap<>();
        catalogServiceSkillRepository.findAllByServiceIdIn(configured).stream()
                .filter(skill -> skill.appliesTo(gvwrClass))
                .sorted(Comparator.comparing(ExtCatalogServiceSkillReplica::getSkillCode))
                .forEach(skill -> required.putIfAbsent(skill.getSkillCode(), skill.getSkillId()));
        return required;
    }

    /**
     * For each required code, the people among {@code persons} holding a credential for it on
     * {@code onDate}. Codes with no holder are absent from the map.
     */
    public @NonNull Map<String, Set<UUID>> holdersBySkill(
            @NonNull Collection<UUID> persons, @NonNull Collection<String> codes, @NonNull LocalDate onDate) {
        if (persons.isEmpty() || codes.isEmpty()) {
            return Map.of();
        }
        return holdersBySkill(credentialsOf(persons), persons, codes, onDate);
    }

    /** Every credential of {@code persons}, for a caller that evaluates several dates against one read. */
    public @NonNull List<ExtPersonCredentialReplica> credentialsOf(@NonNull Collection<UUID> persons) {
        return persons.isEmpty() ? List.of() : credentialRepository.findByPersonIdInOrderByIssuedOnDesc(persons);
    }

    /**
     * As {@link #holdersBySkill(Collection, Collection, LocalDate)}, over credentials already read;
     * only credentials of {@code persons} count, so one read serves several rosters and dates.
     */
    public static @NonNull Map<String, Set<UUID>> holdersBySkill(
            @NonNull List<ExtPersonCredentialReplica> credentials,
            @NonNull Collection<UUID> persons,
            @NonNull Collection<String> codes,
            @NonNull LocalDate onDate) {
        Map<String, Set<UUID>> holders = new LinkedHashMap<>();
        for (ExtPersonCredentialReplica credential : credentials) {
            if (!persons.contains(credential.getPersonId())
                    || !credential.statusOn(onDate).isHeld()) {
                continue;
            }
            for (String code : codes) {
                String normalized = normalize(code);
                if (normalized.equals(normalize(credential.getSkillCode()))
                        || normalized.equals(normalize(credential.getSourceCredentialCode()))) {
                    holders.computeIfAbsent(code, ignored -> new LinkedHashSet<>())
                            .add(credential.getPersonId());
                }
            }
        }
        return holders;
    }

    /** The codes among {@code required} that nobody in {@code holders} holds. */
    public static @NonNull List<String> missing(
            @NonNull Collection<String> required, @NonNull Map<String, Set<UUID>> holders) {
        return required.stream()
                .filter(code -> holders.getOrDefault(code, Set.of()).isEmpty())
                .toList();
    }

    /** Whether the assignment is an ACTIVE technician posting (any date). */
    public static boolean isTechnician(@NonNull ExtStaffingAssignmentReplica assignment) {
        return STAFFING_ACTIVE.equalsIgnoreCase(assignment.getStatus())
                && TECHNICIAN_ROLE.equalsIgnoreCase(assignment.getRole());
    }

    /** Whether the assignment's effective range covers {@code date} (open-ended bounds allowed). */
    public static boolean covers(@NonNull ExtStaffingAssignmentReplica assignment, @NonNull LocalDate date) {
        boolean started = assignment.getEffectiveFrom() == null
                || !assignment.getEffectiveFrom().isAfter(date);
        boolean notEnded = assignment.getEffectiveTo() == null
                || !assignment.getEffectiveTo().isBefore(date);
        return started && notEnded;
    }

    public static @NonNull String normalize(@Nullable String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
