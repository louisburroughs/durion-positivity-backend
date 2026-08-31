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
import org.springframework.stereotype.Component;

/**
 * Pulls role personas from {@code pos-security-service} and applies them (#1613, D4).
 *
 * <p>The mechanism, separate from {@link RolePersonaSyncRunner}, which owns only <em>when</em> a
 * pull happens. They are split because the schedule is startup-and-timer work that should not run in
 * tests, while the on-miss path is request-path work that must be available in every profile —
 * folding both into one profile-gated bean would leave prompt assembly unable to repair itself
 * wherever that gate excluded it.
 *
 * <p>Two things are updated on a successful pull, for different reasons:
 *
 * <ul>
 *   <li>the in-memory {@link RolePersonaSnapshot}, which drives resolution priority and the agent
 *       warm-up set;
 *   <li>one {@code system_prompts} row per eligible role, which is what prompt assembly reads. The
 *       rows are why a sync outage is survivable: the last good personas keep serving.
 * </ul>
 *
 * <p>Fail-soft throughout. A failed fetch leaves the previous snapshot and the persisted rows
 * untouched; the gauges on {@link RolePersonaSnapshotHolder} are what make that visible rather than
 * silent.
 */
@Component
public class RolePersonaRefresher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RolePersonaRefresher.class);

    private final RolePersonaSource personaSource;
    private final RolePersonaSnapshotHolder snapshotHolder;
    private final SystemPromptRepository systemPromptRepository;

    public RolePersonaRefresher(
            @NonNull RolePersonaSource personaSource,
            @NonNull RolePersonaSnapshotHolder snapshotHolder,
            @NonNull SystemPromptRepository systemPromptRepository) {
        this.personaSource = personaSource;
        this.snapshotHolder = snapshotHolder;
        this.systemPromptRepository = systemPromptRepository;
    }

    /**
     * Pulls every persona and swaps the snapshot. Returns whether the sync succeeded, so a caller
     * that can react to a failure is able to.
     */
    public boolean refreshAll() {
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
     * one of its users misses, triggers this, and is served with the persona it just fetched.
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
