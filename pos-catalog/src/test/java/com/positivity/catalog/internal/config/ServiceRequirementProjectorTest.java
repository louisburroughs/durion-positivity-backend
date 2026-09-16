package com.positivity.catalog.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.RequiredSkillDto;
import com.positivity.catalog.internal.entity.ExtSkillReplica;
import com.positivity.catalog.internal.entity.ServiceRequirementProfileEntity;
import com.positivity.catalog.internal.entity.ServiceSkillRequirementEntity;
import com.positivity.catalog.internal.repository.ExtSkillReplicaRepository;
import com.positivity.catalog.internal.repository.ServiceRequirementProfileRepository;
import com.positivity.catalog.internal.repository.ServiceSkillRequirementRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CAP-329: not-configured and unconstrained stay distinguishable on every surface. */
class ServiceRequirementProjectorTest {

    private static final UUID CONFIGURED = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a535");
    private static final UUID UNCONSTRAINED = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a536");
    private static final UUID UNCONFIGURED = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a537");
    private static final UUID HEAVY_BRAKES = UUID.fromString("01960011-0000-7000-8000-000000000041");
    private static final UUID RETIRED = UUID.fromString("01960011-0000-7000-8000-000000000049");
    private static final Instant WHEN = Instant.parse("2026-09-16T12:00:00Z");

    private final ServiceRequirementProfileRepository profiles = mock(ServiceRequirementProfileRepository.class);
    private final ServiceSkillRequirementRepository requirements = mock(ServiceSkillRequirementRepository.class);
    private final ExtSkillReplicaRepository skills = mock(ExtSkillReplicaRepository.class);
    private final ServiceRequirementProjector projector =
            new ServiceRequirementProjector(profiles, requirements, skills);

    @Test
    @DisplayName("configured with skills, configured with none, and never configured are three different answers")
    void threeStatesProjectDifferently() {
        when(profiles.findAllByServiceIdIn(anyCollection()))
                .thenReturn(List.of(
                        ServiceRequirementProfileEntity.builder()
                                .serviceId(CONFIGURED)
                                .configuredAt(WHEN)
                                .build(),
                        ServiceRequirementProfileEntity.builder()
                                .serviceId(UNCONSTRAINED)
                                .configuredAt(WHEN)
                                .build()));
        when(requirements.findAllByServiceIdIn(anyCollection()))
                .thenReturn(List.of(
                        ServiceSkillRequirementEntity.builder()
                                .serviceId(CONFIGURED)
                                .skillId(HEAVY_BRAKES)
                                .minGvwrClass(4)
                                .maxGvwrClass(8)
                                .build(),
                        ServiceSkillRequirementEntity.builder()
                                .serviceId(CONFIGURED)
                                .skillId(RETIRED)
                                .build()));
        when(skills.findAllBySkillIdIn(anyCollection()))
                .thenReturn(List.of(
                        ExtSkillReplica.builder()
                                .skillId(HEAVY_BRAKES)
                                .code("BRAKES-MEDIUM_HEAVY")
                                .competenceCode("BRAKES")
                                .minGvwrClass(4)
                                .maxGvwrClass(8)
                                .active(true)
                                .build(),
                        ExtSkillReplica.builder()
                                .skillId(RETIRED)
                                .code("OLD-THING")
                                .competenceCode("OLD")
                                .minGvwrClass(1)
                                .maxGvwrClass(8)
                                .active(false)
                                .build()));

        Map<UUID, ServiceRequirementProjector.RequirementProfileView> views =
                projector.loadAll(List.of(CONFIGURED, UNCONSTRAINED, UNCONFIGURED));

        assertThat(views).containsOnlyKeys(CONFIGURED, UNCONSTRAINED);
        assertThat(views.get(UNCONSTRAINED).configuredAt()).isEqualTo(WHEN);
        assertThat(views.get(UNCONSTRAINED).requiredSkills()).isEmpty();
        // Sorted by code; the retired skill is listed and flagged, not dropped.
        assertThat(views.get(CONFIGURED).requiredSkills())
                .extracting(RequiredSkillDto::getSkillCode, RequiredSkillDto::isSkillActive)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("BRAKES-MEDIUM_HEAVY", true),
                        org.assertj.core.groups.Tuple.tuple("OLD-THING", false));
        RequiredSkillDto heavy = views.get(CONFIGURED).requiredSkills().getFirst();
        assertThat(heavy.getSkillId()).isEqualTo(HEAVY_BRAKES);
        assertThat(heavy.getCompetenceCode()).isEqualTo("BRAKES");
        assertThat(heavy.getMinGvwrClass()).isEqualTo(4);
        assertThat(heavy.getMaxGvwrClass()).isEqualTo(8);
    }

    @Test
    @DisplayName("no profiles means no further queries")
    void nothingConfiguredShortCircuits() {
        when(profiles.findAllByServiceIdIn(anyCollection())).thenReturn(List.of());

        assertThat(projector.loadAll(List.of(UNCONFIGURED))).isEmpty();
        assertThat(projector.load(UNCONFIGURED)).isEmpty();
        verifyNoInteractions(requirements, skills);
    }
}
