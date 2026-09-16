package com.positivity.people.internal.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.entity.Skill;
import com.positivity.people.internal.repository.SkillRepository;
import com.positivity.tenancy.TenantResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CAP-329: the seeded registry is published whole at startup, and skipped when no tenant resolves. */
class SkillRegistryFactPublisherTest {

    private final SkillRepository skillRepository = mock(SkillRepository.class);
    private final PeopleEventPublisher peopleEventPublisher = mock(PeopleEventPublisher.class);
    private final TenantResolver tenantResolver = mock(TenantResolver.class);
    private final SkillRegistryFactPublisher publisher =
            new SkillRegistryFactPublisher(skillRepository, peopleEventPublisher, tenantResolver);

    private static Skill skill(String code) {
        return Skill.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(code)
                .competenceCode(code)
                .minGvwrClass(1)
                .maxGvwrClass(8)
                .active(true)
                .build();
    }

    @Test
    @DisplayName("every registry row is published, retired ones included")
    void publishesEveryRow() {
        when(tenantResolver.resolve()).thenReturn(Optional.of(UUID.randomUUID()));
        Skill brakes = skill("BRAKES-LIGHT");
        Skill retired = skill("RETIRED");
        retired.setActive(false);
        when(skillRepository.findAll()).thenReturn(List.of(brakes, retired));

        publisher.publishRegistry();

        verify(peopleEventPublisher).publishSkillUpdated(brakes);
        verify(peopleEventPublisher).publishSkillUpdated(retired);
    }

    @Test
    @DisplayName("without a resolvable tenant the outbox could not stamp a row, so nothing is queued")
    void skipsWhenNoTenantResolves() {
        when(tenantResolver.resolve()).thenReturn(Optional.empty());

        publisher.publishRegistry();

        verifyNoInteractions(skillRepository);
        verify(peopleEventPublisher, never()).publishSkillUpdated(any());
    }
}
