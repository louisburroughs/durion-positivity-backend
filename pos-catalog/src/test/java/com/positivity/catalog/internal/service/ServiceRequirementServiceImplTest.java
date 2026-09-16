package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** CAP-329: the write side — replica-validated, replace-set, and versioned onto the service fact. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceRequirementServiceImplTest {

    private static final UUID SERVICE_ID = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a535");
    private static final UUID LIGHT_BRAKES = UUID.fromString("01960011-0000-7000-8000-000000000040");
    private static final UUID HEAVY_BRAKES = UUID.fromString("01960011-0000-7000-8000-000000000041");
    private static final UUID RETIRED = UUID.fromString("01960011-0000-7000-8000-000000000049");
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private ServiceRequirementProfileRepository profileRepository;

    @Mock
    private ServiceSkillRequirementRepository requirementRepository;

    @Mock
    private ExtSkillReplicaRepository skillReplicaRepository;

    @Mock
    private ServiceRequirementProjector projector;

    @Mock
    private CatalogFactPublisher catalogFactPublisher;

    @Mock
    private CatalogService catalogService;

    @Mock
    private EntityManager entityManager;

    private ServiceRequirementServiceImpl service;
    private ServiceEntity brakeJob;

    @BeforeEach
    void setUp() {
        service = new ServiceRequirementServiceImpl(
                serviceRepository,
                profileRepository,
                requirementRepository,
                skillReplicaRepository,
                projector,
                catalogFactPublisher,
                catalogService,
                entityManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
        brakeJob = new ServiceEntity();
        brakeJob.setId(SERVICE_ID);
        brakeJob.setName("Brake pad replacement, front");
        when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(brakeJob));
        when(profileRepository.findById(SERVICE_ID)).thenReturn(Optional.empty());
        when(skillReplicaRepository.findAllBySkillIdIn(anyCollection()))
                .thenReturn(List.of(
                        skill(LIGHT_BRAKES, "BRAKES-LIGHT", 1, 3, true),
                        skill(HEAVY_BRAKES, "BRAKES-MEDIUM_HEAVY", 4, 8, true),
                        skill(RETIRED, "OLD-THING", 1, 8, false)));
        ServiceDto dto = new ServiceDto();
        dto.setId(SERVICE_ID);
        when(catalogService.getServiceById(SERVICE_ID)).thenReturn(Optional.of(dto));
    }

    private static ExtSkillReplica skill(UUID id, String code, int min, int max, boolean active) {
        return ExtSkillReplica.builder()
                .skillId(id)
                .code(code)
                .name(code)
                .competenceCode(code.split("-")[0])
                .minGvwrClass(min)
                .maxGvwrClass(max)
                .active(active)
                .aggregateVersion(1)
                .updatedAt(NOW)
                .build();
    }

    private static ServiceRequirementsRequest request(RequiredSkillRequest... skills) {
        return ServiceRequirementsRequest.builder()
                .requiredSkills(List.of(skills))
                .build();
    }

    private static RequiredSkillRequest required(UUID skillId, Integer min, Integer max) {
        return RequiredSkillRequest.builder()
                .skillId(skillId)
                .minGvwrClass(min)
                .maxGvwrClass(max)
                .build();
    }

    @Test
    @DisplayName("one unforked service requires A-series brakes on classes 1-3 and T-series on 4-8")
    void classConditionalDeclarationIsStoredAndPublished() {
        service.setRequirements(
                SERVICE_ID, request(required(LIGHT_BRAKES, 1, 3), required(HEAVY_BRAKES, 4, 8)), "maya");

        ArgumentCaptor<ServiceRequirementProfileEntity> profile =
                ArgumentCaptor.forClass(ServiceRequirementProfileEntity.class);
        verify(profileRepository).save(profile.capture());
        assertThat(profile.getValue().getServiceId()).isEqualTo(SERVICE_ID);
        assertThat(profile.getValue().getConfiguredAt()).isEqualTo(NOW);
        assertThat(profile.getValue().getConfiguredBy()).isEqualTo("maya");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ServiceSkillRequirementEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(requirementRepository).deleteAllByServiceId(SERVICE_ID);
        verify(requirementRepository).saveAll(rows.capture());
        assertThat(rows.getValue())
                .extracting(
                        ServiceSkillRequirementEntity::getSkillId,
                        ServiceSkillRequirementEntity::getMinGvwrClass,
                        ServiceSkillRequirementEntity::getMaxGvwrClass)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LIGHT_BRAKES, 1, 3),
                        org.assertj.core.groups.Tuple.tuple(HEAVY_BRAKES, 4, 8));

        // The service row is untouched by the write, so its version is forced up before the fact
        // is published — otherwise consumers would discard the fact as stale.
        var order = inOrder(entityManager, catalogFactPublisher);
        order.verify(entityManager).lock(brakeJob, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        order.verify(entityManager).flush();
        order.verify(catalogFactPublisher).publishServiceUpdated(brakeJob);
    }

    @Test
    @DisplayName("an empty declaration writes the header with no children: unconstrained, not unconfigured")
    void emptyDeclarationIsUnconstrained() {
        service.setRequirements(SERVICE_ID, request(), "maya");

        verify(profileRepository).save(any(ServiceRequirementProfileEntity.class));
        verify(requirementRepository).deleteAllByServiceId(SERVICE_ID);
        verify(requirementRepository).saveAll(List.of());
        verify(skillReplicaRepository, never()).findAllBySkillIdIn(anyCollection());
        verify(catalogFactPublisher).publishServiceUpdated(brakeJob);
    }

    @Test
    @DisplayName("a re-declaration keeps the profile row and refreshes configuredAt/By")
    void redeclarationUpdatesExistingProfile() {
        ServiceRequirementProfileEntity existing = ServiceRequirementProfileEntity.builder()
                .serviceId(SERVICE_ID)
                .configuredAt(NOW.minusSeconds(86_400))
                .configuredBy("earlier")
                .build();
        when(profileRepository.findById(SERVICE_ID)).thenReturn(Optional.of(existing));

        service.setRequirements(SERVICE_ID, request(required(HEAVY_BRAKES, null, null)), "maya");

        assertThat(existing.getConfiguredAt()).isEqualTo(NOW);
        assertThat(existing.getConfiguredBy()).isEqualTo("maya");
        verify(profileRepository).save(existing);
    }

    @Test
    @DisplayName("ANY class is both bounds omitted; the skill's own range then decides at resolution time")
    void anyClassNeedsNoBounds() {
        service.setRequirements(SERVICE_ID, request(required(HEAVY_BRAKES, null, null)), "maya");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ServiceSkillRequirementEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(requirementRepository).saveAll(rows.capture());
        assertThat(rows.getValue().getFirst().appliesToAnyClass()).isTrue();
    }

    @Test
    void unknownServiceIs404() {
        when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setRequirements(SERVICE_ID, request(), "maya"))
                .isInstanceOf(CatalogNotFoundException.class);
        verify(profileRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "a skill id the registry replica does not hold is refused, naming it — never stored as a skill nobody has")
    void unknownSkillIs422() {
        UUID unknown = UUID.fromString("01960011-0000-7000-8000-0000000000ff");

        assertThatThrownBy(() -> service.setRequirements(SERVICE_ID, request(required(unknown, null, null)), "maya"))
                .isInstanceOf(CatalogUnprocessableException.class)
                .satisfies(e -> assertThat(((CatalogUnprocessableException) e).getCode())
                        .isEqualTo(ServiceRequirementServiceImpl.SKILL_UNKNOWN))
                .hasMessageContaining(unknown.toString());
        verify(profileRepository, never()).save(any());
        verify(catalogFactPublisher, never()).publishServiceUpdated(any());
    }

    @Test
    void retiredSkillIs422() {
        assertThatThrownBy(() -> service.setRequirements(SERVICE_ID, request(required(RETIRED, null, null)), "maya"))
                .isInstanceOf(CatalogUnprocessableException.class)
                .satisfies(e -> assertThat(((CatalogUnprocessableException) e).getCode())
                        .isEqualTo(ServiceRequirementServiceImpl.SKILL_RETIRED))
                .hasMessageContaining("OLD-THING");
    }

    @Test
    void duplicateSkillIs422() {
        assertThatThrownBy(() -> service.setRequirements(
                        SERVICE_ID, request(required(HEAVY_BRAKES, 4, 8), required(HEAVY_BRAKES, null, null)), "maya"))
                .isInstanceOf(CatalogUnprocessableException.class)
                .satisfies(e -> assertThat(((CatalogUnprocessableException) e).getCode())
                        .isEqualTo(ServiceRequirementServiceImpl.SKILL_DUPLICATE));
    }

    @Test
    @DisplayName("T4 brakes certify classes 4-8: requiring them for class 3 names a competence outside its scope")
    void rangeOutsideTheSkillsOwnIs422() {
        assertThatThrownBy(() -> service.setRequirements(SERVICE_ID, request(required(HEAVY_BRAKES, 3, 8)), "maya"))
                .isInstanceOf(CatalogUnprocessableException.class)
                .satisfies(e -> assertThat(((CatalogUnprocessableException) e).getCode())
                        .isEqualTo(ServiceRequirementServiceImpl.SKILL_CLASS_RANGE_INVALID))
                .hasMessageContaining("4-8");
    }

    @Test
    void halfOpenOrInvertedRangeIs422() {
        assertThatThrownBy(() -> service.setRequirements(SERVICE_ID, request(required(HEAVY_BRAKES, 4, null)), "maya"))
                .isInstanceOf(CatalogUnprocessableException.class)
                .satisfies(e -> assertThat(((CatalogUnprocessableException) e).getCode())
                        .isEqualTo(ServiceRequirementServiceImpl.SKILL_CLASS_RANGE_INVALID));
        assertThatThrownBy(() -> service.setRequirements(SERVICE_ID, request(required(HEAVY_BRAKES, 8, 4)), "maya"))
                .isInstanceOf(CatalogUnprocessableException.class);
    }
}
