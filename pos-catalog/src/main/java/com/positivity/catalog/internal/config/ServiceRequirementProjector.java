package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.dto.RequiredSkillDto;
import com.positivity.catalog.internal.entity.ExtSkillReplica;
import com.positivity.catalog.internal.entity.ServiceRequirementProfileEntity;
import com.positivity.catalog.internal.entity.ServiceSkillRequirementEntity;
import com.positivity.catalog.internal.repository.ExtSkillReplicaRepository;
import com.positivity.catalog.internal.repository.ServiceRequirementProfileRepository;
import com.positivity.catalog.internal.repository.ServiceSkillRequirementRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Reads a service's requirement profile the one way every surface shows it (CAP-329): the read
 * DTOs, the {@code catalog.service.updated} fact and the write endpoint's response all project
 * through here, so "not configured" (no profile) and "unconstrained" (profile, no skills) cannot
 * drift apart between them. Skill code, competence and active flag are joined from the
 * {@code ext_skill} replica; a skill the registry has since retired is still listed, flagged.
 */
@Component
@RequiredArgsConstructor
public class ServiceRequirementProjector {

    private final ServiceRequirementProfileRepository profileRepository;
    private final ServiceSkillRequirementRepository requirementRepository;
    private final ExtSkillReplicaRepository skillReplicaRepository;

    /** A service's declared requirements; absent when the service was never configured. */
    public record RequirementProfileView(
            @NonNull Instant configuredAt, @NonNull List<RequiredSkillDto> requiredSkills) {}

    public Optional<RequirementProfileView> load(@NonNull UUID serviceId) {
        return Optional.ofNullable(loadAll(List.of(serviceId)).get(serviceId));
    }

    /** Profiles for many services in three queries, keyed by service id; unconfigured services are absent. */
    public @NonNull Map<UUID, RequirementProfileView> loadAll(@NonNull Collection<UUID> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        List<ServiceRequirementProfileEntity> profiles = profileRepository.findAllByServiceIdIn(serviceIds);
        if (profiles.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<ServiceSkillRequirementEntity>> requirementsByService =
                requirementRepository.findAllByServiceIdIn(serviceIds).stream()
                        .collect(Collectors.groupingBy(ServiceSkillRequirementEntity::getServiceId));
        List<UUID> skillIds = requirementsByService.values().stream()
                .flatMap(List::stream)
                .map(ServiceSkillRequirementEntity::getSkillId)
                .distinct()
                .toList();
        Map<UUID, ExtSkillReplica> skills = skillIds.isEmpty()
                ? Map.of()
                : skillReplicaRepository.findAllBySkillIdIn(skillIds).stream()
                        .collect(Collectors.toMap(ExtSkillReplica::getSkillId, Function.identity()));
        Map<UUID, RequirementProfileView> views = new HashMap<>();
        for (ServiceRequirementProfileEntity profile : profiles) {
            List<RequiredSkillDto> required =
                    requirementsByService.getOrDefault(profile.getServiceId(), List.of()).stream()
                            .map(requirement -> toDto(requirement, skills.get(requirement.getSkillId())))
                            .sorted(Comparator.comparing(
                                    RequiredSkillDto::getSkillCode, Comparator.nullsLast(Comparator.naturalOrder())))
                            .toList();
            views.put(profile.getServiceId(), new RequirementProfileView(profile.getConfiguredAt(), required));
        }
        return views;
    }

    private static RequiredSkillDto toDto(ServiceSkillRequirementEntity requirement, ExtSkillReplica skill) {
        return RequiredSkillDto.builder()
                .skillId(requirement.getSkillId())
                .skillCode(skill == null ? null : skill.getCode())
                .competenceCode(skill == null ? null : skill.getCompetenceCode())
                .minGvwrClass(requirement.getMinGvwrClass())
                .maxGvwrClass(requirement.getMaxGvwrClass())
                .skillActive(skill != null && skill.isActive())
                .build();
    }
}
