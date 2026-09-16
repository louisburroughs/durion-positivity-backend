package com.positivity.people.internal.config;

import com.positivity.people.internal.entity.Skill;
import com.positivity.people.internal.repository.SkillRepository;
import com.positivity.tenancy.TenantResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes the whole skill registry as {@code people.skill.updated} facts once the application
 * is ready (CAP-329, ADR-0044 §6).
 *
 * <p>The registry is seeded reference data with no write endpoint, so nothing else would ever
 * emit a fact for it; consumers that validate skill references (pos-catalog's requirement
 * profile) need a replica all the same. Each row is versioned by its {@code updatedAt}, so a
 * restart re-sends what consumers already hold — applied idempotently — and a re-seed that
 * touches a row reaches them as a newer version.
 *
 * <p>The outbox stamps every row with the resolved tenant. A global fact has no tenant of its
 * own; while the transitional default tenant is configured it is stamped with that, and when
 * nothing resolves the publication is skipped with a warning rather than failing startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillRegistryFactPublisher {

    private final SkillRepository skillRepository;
    private final PeopleEventPublisher peopleEventPublisher;
    private final TenantResolver tenantResolver;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void publishRegistry() {
        if (tenantResolver.resolve().isEmpty()) {
            log.warn("No tenant resolvable at startup; skill registry facts not published");
            return;
        }
        List<Skill> skills = skillRepository.findAll();
        skills.forEach(peopleEventPublisher::publishSkillUpdated);
        log.info("Queued people.skill.updated for {} registry rows", skills.size());
    }
}
