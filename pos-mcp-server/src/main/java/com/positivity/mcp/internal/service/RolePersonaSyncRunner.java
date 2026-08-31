package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.RoleAuthorities;
import com.positivity.mcp.internal.domain.RolePersona;
import com.positivity.mcp.internal.domain.RolePersonaRenderer;
import com.positivity.mcp.internal.domain.RolePersonaSnapshot;
import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code pos-mcp-server}'s role personas in step with {@code pos-security-service} (#1613, D4).
 *
 * <p>Replaces {@code SystemPromptSeedRunner.seedRolePersonas}, which wrote one hand-authored block
 * per role at startup and therefore could not see a role created after the release it shipped in.
 *
 * <p>Two things are updated on every sync, for different reasons:
 *
 * <ul>
 *   <li>the in-memory {@link RolePersonaSnapshot}, which drives resolution priority and the agent
 *       warm-up set;
 *   <li>one {@code system_prompts} row per eligible role, which is what prompt assembly reads. The
 *       rows are the reason a sync outage is survivable: the last good personas keep serving while
 *       the fetch is failing.
 * </ul>
 *
 * <p>Fail-soft throughout. A failed fetch leaves the previous snapshot and the persisted rows
 * untouched; the gauges on {@link RolePersonaSnapshotHolder} are what make that visible rather than
 * silent.
 */
@Component
@Profile("!test")
public class RolePersonaSyncRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RolePersonaSyncRunner.class);

    private final RolePersonaSource personaSource;
    private final RolePersonaSnapshotHolder snapshotHolder;
    private final SystemPromptRepository systemPromptRepository;

    public RolePersonaSyncRunner(
            @NonNull RolePersonaSource personaSource,
            @NonNull RolePersonaSnapshotHolder snapshotHolder,
            @NonNull SystemPromptRepository systemPromptRepository) {
        this.personaSource = personaSource;
        this.snapshotHolder = snapshotHolder;
        this.systemPromptRepository = systemPromptRepository;
    }

    /** Tier 1: startup pull. */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        refresh();
    }

    /**
     * Tier 3: periodic re-pull.
     *
     * <p>A safety net rather than the primary trigger — role changes arrive by event — so the
     * default interval is generous. It still matters: an event that is dropped, or a persona edited
     * while this service was down, is otherwise invisible until the next restart.
     */
    @Scheduled(
            initialDelayString = "${mcp.role-persona.refresh-interval:PT15M}",
            fixedDelayString = "${mcp.role-persona.refresh-interval:PT15M}")
    public void scheduledRefresh() {
        refresh();
    }

    /**
     * Pulls every persona and swaps the snapshot. Returns whether the sync succeeded, so callers
     * that can react to a failure — such as an event-triggered refresh — are able to.
     */
    public boolean refresh() {
        Optional<RolePersonaSource.RolePersonaSnapshotData> fetched = personaSource.fetchAll();
        if (fetched.isEmpty()) {
            LOGGER.warn(
                    "MCP role persona sync failed; keeping previous snapshot roleCount={} generatedAt={}",
                    snapshotHolder.get().roleCount(),
                    snapshotHolder.get().generatedAt());
            return false;
        }

        RolePersonaSource.RolePersonaSnapshotData data = fetched.get();
        RolePersonaSnapshot previous = snapshotHolder.get();
        RolePersonaSnapshot snapshot = RolePersonaSnapshot.of(data.generatedAt(), data.personas());
        snapshotHolder.set(snapshot);
        persistPersonas(snapshot);

        LOGGER.info(
                "MCP role persona sync complete roles={} (was {}) ineligible={} generatedAt={}",
                snapshot.roleCount(),
                previous.roleCount(),
                data.personas().size() - snapshot.roleCount(),
                snapshot.generatedAt());
        return true;
    }

    /**
     * Fetches a single role and merges it into the held snapshot (tier 2, the lazy on-miss path).
     *
     * <p>This is what makes a role created after boot work without a restart: the first request from
     * one of its users misses, triggers this, and the next request — and the rest of that one — has
     * the persona.
     */
    public boolean refreshRole(@NonNull String authority) {
        Optional<RolePersona> fetched = personaSource.fetchOne(RoleAuthorities.toRoleName(authority));
        if (fetched.isEmpty()) {
            return false;
        }

        RolePersona persona = fetched.get();
        snapshotHolder.merge(persona);
        if (persona.mcpPersonaEligible()) {
            upsert(RoleAuthorities.toAuthority(persona.name()), RolePersonaRenderer.render(persona));
        }
        LOGGER.info(
                "MCP role persona fetched on miss role={} eligible={}", persona.name(), persona.mcpPersonaEligible());
        return true;
    }

    /**
     * Writes one row per eligible role.
     *
     * <p>Deliberately not wrapped in a single transaction: each row is saved on its own so one bad
     * persona cannot roll back the rest of the sync, which matters because this runs at startup and
     * a partial refresh is worth more than none.
     *
     * <p>Rows for roles that have disappeared upstream are left alone. Resolution reads the snapshot,
     * so a stale row is never selected, and deleting rows here would also delete a persona an
     * administrator had edited through {@code SystemPromptController}.
     */
    private void persistPersonas(@NonNull RolePersonaSnapshot snapshot) {
        for (String authority : snapshot.rankedAuthorities()) {
            snapshot.personaText(authority).ifPresent(text -> upsert(authority, text));
        }
    }

    private void upsert(@NonNull String name, @NonNull String content) {
        try {
            Optional<SystemPrompt> existing = systemPromptRepository.findByName(name);
            if (existing.isPresent()) {
                SystemPrompt prompt = existing.get();
                if (!content.equals(prompt.getContent())) {
                    prompt.setContent(content);
                    systemPromptRepository.save(prompt);
                    LOGGER.info("Updated role persona prompt name={}", name);
                }
                return;
            }

            SystemPrompt prompt = new SystemPrompt();
            prompt.setName(name);
            prompt.setContent(content);
            systemPromptRepository.save(prompt);
            LOGGER.info("Seeded role persona prompt name={}", name);
        } catch (Exception exception) {
            // One bad row must not abandon the rest of the sync.
            LOGGER.warn("Failed to persist role persona prompt name={}", name, exception);
        }
    }
}
