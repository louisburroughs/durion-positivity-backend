package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.config.ServiceRequirementProjector;
import com.positivity.catalog.internal.dto.ServiceDto;
import com.positivity.catalog.internal.dto.ServiceRequirementsRequest;
import com.positivity.catalog.internal.dto.ServiceRequirementsRequest.RequiredSkillRequest;
import com.positivity.catalog.internal.entity.ExtSkillReplica;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.entity.ServiceRequirementProfileEntity;
import com.positivity.catalog.internal.entity.ServiceSkillRequirementEntity;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogUnprocessableException;
import com.positivity.catalog.internal.repository.ExtSkillReplicaRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import com.positivity.catalog.internal.repository.ServiceRequirementProfileRepository;
import com.positivity.catalog.internal.repository.ServiceSkillRequirementRepository;
import com.positivity.web.common.ReplicationPendingException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a service's skill requirement profile (CAP-329).
 *
 * <p>Validation is against the {@code ext_skill} replica, never a call into pos-people (ADR-0044
 * §6), and it is strict on write: a retired skill id and a class range the skill's own range does
 * not cover are 422. A skill id the replica does not hold at all is 503
 * {@code SKILL_REPLICATION_PENDING} instead (#1994), because absence there may only mean "not
 * replicated yet". SOFT (spec D10) is the search-time treatment of an
 * <em>unmet</em> requirement, never the write-time treatment of an <em>invalid</em> one.
 *
 * <p>The declaration is replace-set — what is sent is what the service requires afterwards — and
 * the service's version is force-incremented so the {@code catalog.service.updated} fact that
 * follows outranks every earlier one on the consumers' stale guard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceRequirementServiceImpl implements ServiceRequirementService {

    static final String SKILL_REPLICATION_PENDING = "SKILL_REPLICATION_PENDING";
    static final String SKILL_RETIRED = "SKILL_RETIRED";
    static final String SKILL_DUPLICATE = "SKILL_DUPLICATE";
    static final String SKILL_CLASS_RANGE_INVALID = "SKILL_CLASS_RANGE_INVALID";

    private final ServiceRepository serviceRepository;
    private final ServiceRequirementProfileRepository profileRepository;
    private final ServiceSkillRequirementRepository requirementRepository;
    private final ExtSkillReplicaRepository skillReplicaRepository;
    private final ServiceRequirementProjector projector;
    private final CatalogFactPublisher catalogFactPublisher;
    private final CatalogService catalogService;
    private final EntityManager entityManager;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull ServiceDto setRequirements(
            @NonNull UUID serviceId, @NonNull ServiceRequirementsRequest request, @NonNull String actor) {
        ServiceEntity service = serviceRepository
                .findById(serviceId)
                .orElseThrow(() -> new CatalogNotFoundException("Service not found: " + serviceId));
        List<RequiredSkillRequest> declared = request.getRequiredSkills();
        Map<UUID, ExtSkillReplica> skills = resolveSkills(declared);

        ServiceRequirementProfileEntity profile = profileRepository
                .findById(serviceId)
                .orElseGet(() -> ServiceRequirementProfileEntity.builder()
                        .serviceId(serviceId)
                        .build());
        profile.setConfiguredAt(Instant.now(clock));
        profile.setConfiguredBy(actor);
        profileRepository.save(profile);

        requirementRepository.deleteAllByServiceId(serviceId);
        requirementRepository.saveAll(declared.stream()
                .map(skill -> ServiceSkillRequirementEntity.builder()
                        .serviceId(serviceId)
                        .skillId(skill.getSkillId())
                        .minGvwrClass(skill.getMinGvwrClass())
                        .maxGvwrClass(skill.getMaxGvwrClass())
                        .build())
                .toList());

        // The fact is versioned by the service row, which this write does not otherwise touch.
        entityManager.lock(service, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        entityManager.flush();
        catalogFactPublisher.publishServiceUpdated(service);
        log.info(
                "Service {} requirements set by {}: {} skill(s) [{}]",
                serviceId,
                actor,
                declared.size(),
                skills.values().stream().map(ExtSkillReplica::getCode).sorted().collect(Collectors.joining(", ")));
        return catalogService
                .getServiceById(serviceId)
                .orElseThrow(
                        () -> new IllegalStateException("Service vanished after requirements write: " + serviceId));
    }

    /**
     * Every declared skill must be in the replica and active, named once, and — when a class
     * range is given — the range must be well-formed and lie within the skill's own range: T4
     * brakes certify classes 4–8, so requiring it for class 3 names a competence outside its scope.
     */
    private Map<UUID, ExtSkillReplica> resolveSkills(List<RequiredSkillRequest> declared) {
        if (declared.isEmpty()) {
            return Map.of();
        }
        Set<UUID> seen = new HashSet<>();
        for (RequiredSkillRequest skill : declared) {
            if (!seen.add(skill.getSkillId())) {
                throw new CatalogUnprocessableException(
                        SKILL_DUPLICATE, "Skill " + skill.getSkillId() + " is named more than once");
            }
        }
        Map<UUID, ExtSkillReplica> skills = skillReplicaRepository.findAllBySkillIdIn(seen).stream()
                .collect(Collectors.toMap(ExtSkillReplica::getSkillId, Function.identity()));
        for (RequiredSkillRequest declaredSkill : declared) {
            ExtSkillReplica skill = skills.get(declaredSkill.getSkillId());
            if (skill == null) {
                // The skill registry is owned by pos-people and arrives here by event, so an id
                // with no ext_skill row may simply not have replicated yet: 503, not a 422 the
                // caller would take for a wrong id (#1994). Retired and out-of-range skills below
                // are present rows in the wrong state and keep their 422.
                throw new ReplicationPendingException(
                        SKILL_REPLICATION_PENDING,
                        "A declared skill has not replicated from the skill registry yet; retry shortly",
                        declaredSkill.getSkillId());
            }
            if (!skill.isActive()) {
                throw new CatalogUnprocessableException(
                        SKILL_RETIRED, "Skill " + skill.getCode() + " has been retired from the registry");
            }
            validateRange(declaredSkill, skill);
        }
        return skills;
    }

    private static void validateRange(RequiredSkillRequest declared, ExtSkillReplica skill) {
        Integer min = declared.getMinGvwrClass();
        Integer max = declared.getMaxGvwrClass();
        if (min == null && max == null) {
            return;
        }
        if (min == null || max == null || min > max) {
            throw new CatalogUnprocessableException(
                    SKILL_CLASS_RANGE_INVALID,
                    "Skill " + skill.getCode() + ": a class range needs both bounds with min <= max (got " + min + ".."
                            + max + "), or neither for ANY");
        }
        if (min < skill.getMinGvwrClass() || max > skill.getMaxGvwrClass()) {
            throw new CatalogUnprocessableException(
                    SKILL_CLASS_RANGE_INVALID,
                    "Skill " + skill.getCode() + " certifies GVWR classes " + skill.getMinGvwrClass() + "-"
                            + skill.getMaxGvwrClass() + "; a requirement for classes " + min + "-" + max
                            + " names a competence outside its scope");
        }
    }
}
